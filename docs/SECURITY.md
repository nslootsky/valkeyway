# Authentication and TLS

ValkeyWay has two independent sides, each with its own settings:

- **Clients → proxy:** passwords (`proxy.auth.*`) and TLS (`proxy.tls.*`), checked by the proxy itself.
- **Proxy → cluster:** a username/password and TLS (`proxy.backend.*`), used for every connection the proxy makes to the nodes.

Everything is off by default. Turn on only the parts you need: for example, client passwords with plain text to a cluster on a private network, or TLS to a managed cluster with no client passwords.

## Upgrading an existing deployment

No configuration change is needed. With none of these settings, the proxy behaves as before: no client authentication, plain text on `proxy.port`, and plain connections to the cluster without AUTH. AUTH from a client answers as a server without passwords does.

`proxy.connect-timeout-ms` was renamed to `proxy.request-timeout-ms`. The old name still works, with a deprecation warning at startup.

## Setting secrets

Keep passwords out of `application.yml`. Every setting can come from an environment variable, named by dropping the dashes, replacing dots with underscores, and uppercasing: `proxy.tls.cert-file` is `PROXY_TLS_CERTFILE`. A named user's password is `PROXY_AUTH_USERS_<NAME>`.

An empty value means "not set", so `password: ${VALKEY_PASSWORD:}` in YAML is safe when the variable is missing.

`PROXY CONFIG GET *` shows whether a password is set (`(set)`), never the password itself.

## Client passwords

```yaml
environment:
  PROXY_AUTH_PASSWORD: ${PROXY_PASSWORD}    # the default user: AUTH <password>
  PROXY_AUTH_USERS_APP: ${APP_PASSWORD}     # optional named users: AUTH app <password>
```

With a password for the default user, clients must authenticate before anything else. Until then every command but AUTH, HELLO, QUIT and RESET gets `NOAUTH Authentication required.`, and requests are limited to 10 arguments of 16 KB each, as on Valkey. Named users alone, without a default password, leave the default user passwordless (no authentication required), as on a server.

Clients connect the way they connect to any password-protected Valkey:

```bash
valkey-cli -h proxy -a "$PROXY_PASSWORD"
valkey-cli -h proxy --user app --pass "$APP_PASSWORD"
```

- URLs: `redis://:password@proxy:6379`, `redis://app:password@proxy:6379`.
- Glide: `.credentials(ServerCredentials.builder().username("app").password(...).build())`.
- Clients that authenticate with `HELLO 3 AUTH user pass` (Lettuce, redis-py with RESP3) work too.

Things to know:
- There are no ACL rules: every user can run every command. `ACL WHOAMI` names the proxy user; other ACL subcommands go to a node and describe the cluster's users.
- `RESET` logs the connection out, as on Valkey.
- `CLIENT LIST` shows each connection's user, and `CLIENT KILL USER app` disconnects a user's connections.
- Passwords are read at startup; changing one needs a restart.

## TLS for clients

Mount a PEM certificate chain and private key, for example from cert-manager or a Kubernetes secret, and give the proxy a TLS port:

```yaml
environment:
  PROXY_TLS_PORT: 6379
  PROXY_PORT: 0                       # TLS only. Or keep a plain-text port as well, e.g. 6378.
  PROXY_TLS_CERTFILE: /tls/tls.crt
  PROXY_TLS_KEYFILE: /tls/tls.key
volumes:
  - ./tls:/tls:ro
```

Clients then use TLS:

```bash
valkey-cli -h proxy --tls --cacert ca.crt -a "$PROXY_PASSWORD"
```

URLs use `rediss://` instead of `redis://`.

- **Certificate rotation:** the files are checked every 60 seconds. When they change, new connections get the new certificate; existing connections keep theirs. If the new files can't be loaded (for example, the key was written before the certificate), the proxy keeps the old certificate, logs a warning, and tries again at the next check.
- **Key format:** the key must be PKCS#8, `BEGIN PRIVATE KEY` (or `BEGIN ENCRYPTED PRIVATE KEY`, with `PROXY_TLS_KEYPASSWORD`). Convert an RSA or EC key with `openssl pkcs8 -topk8 -nocrypt -in old.key -out tls.key`.
- **Protocol versions:** `PROXY_TLS_PROTOCOLS=TLSv1.3` limits them; the default is the JDK's.
- **Port clash:** port 6380 is the management port by default, so don't put TLS there.

### Client certificates (mutual TLS)

```yaml
environment:
  PROXY_TLS_CAFILE: /tls/ca.crt       # the CA that signs client certificates
  PROXY_TLS_CLIENTCERTS: required     # or optional
```

With `required`, a client without a certificate signed by that CA can't connect. With `optional`, a client may present one or not. A client certificate doesn't replace a password: with `proxy.auth.password` set, clients must still AUTH.

```bash
valkey-cli -h proxy --tls --cacert ca.crt --cert client.crt --key client.key
```

## TLS and credentials to the cluster

```yaml
environment:
  PROXY_BACKEND_TLS_ENABLED: "true"
  PROXY_BACKEND_PASSWORD: ${VALKEY_PASSWORD}       # an AUTH token or ACL user's password
  PROXY_BACKEND_USERNAME: proxy                    # only for an ACL user; unset = default
  PROXY_BACKEND_TLS_CAFILE: /tls/cluster-ca.crt    # only if the system trust store doesn't cover it
```

For example:
- **ElastiCache with in-transit encryption and an AUTH token:** `PROXY_BACKEND_TLS_ENABLED=true` and `PROXY_BACKEND_PASSWORD` set to the token. With RBAC, also set the username.
- **A self-managed cluster with `requirepass`:** `PROXY_BACKEND_PASSWORD` only.
- **A self-managed cluster with ACL users and TLS:** give the proxy its own user (for example `ACL SETUSER proxy on >password ~* &* +@all`), then set the username, password, TLS and the CA file.

Things to know:
- **Certificate names.** The proxy checks each node's certificate against the address the cluster reports for that node (CLUSTER SLOTS), which is often an IP rather than a host name. The certificates must name those addresses, or you'll see certificate errors. If they can't, `PROXY_BACKEND_TLS_INSECURE=true` skips verification; the connection is still encrypted, but no longer protected against impersonation.
- **No client certificate.** The proxy can't present a client certificate to the cluster (a Glide limitation). A cluster with `tls-auth-clients yes`, Valkey's default once TLS is on, refuses it; set `tls-auth-clients no` or `optional`.
- **What's tested.** The test suite covers a self-hosted TLS-only cluster with ACL users and certificates for its node IPs (`BackendTlsTest`), including blocking commands and WATCH. It hasn't been run against ElastiCache or MemoryDB.

## The management port

Health checks and metrics (`/actuator/*` on port 6380) are plain HTTP without authentication, whatever the settings above. Keep that port on an internal network, or bind it to localhost with `management.server.address=127.0.0.1`.

## Reference

| Setting | Environment variable | Default | Meaning |
|---|---|---|---|
| `proxy.auth.password` | `PROXY_AUTH_PASSWORD` | unset | The default user's password; set = clients must AUTH |
| `proxy.auth.users.<name>` | `PROXY_AUTH_USERS_<NAME>` | none | Named users and their passwords |
| `proxy.port` | `PROXY_PORT` | 6379 | Plain-text port; 0 = none (needs a TLS port) |
| `proxy.tls.port` | `PROXY_TLS_PORT` | 0 (off) | TLS port |
| `proxy.tls.cert-file` | `PROXY_TLS_CERTFILE` | | PEM certificate chain |
| `proxy.tls.key-file` | `PROXY_TLS_KEYFILE` | | PEM private key (PKCS#8) |
| `proxy.tls.key-password` | `PROXY_TLS_KEYPASSWORD` | unset | Password of an encrypted key |
| `proxy.tls.ca-file` | `PROXY_TLS_CAFILE` | unset | CA for client certificates |
| `proxy.tls.client-certs` | `PROXY_TLS_CLIENTCERTS` | none | `none`, `optional` or `required` |
| `proxy.tls.protocols` | `PROXY_TLS_PROTOCOLS` | JDK default | e.g. `TLSv1.2,TLSv1.3` |
| `proxy.backend.username` | `PROXY_BACKEND_USERNAME` | unset (`default`) | User for the cluster's AUTH |
| `proxy.backend.password` | `PROXY_BACKEND_PASSWORD` | unset (no AUTH) | Password for the cluster's AUTH |
| `proxy.backend.tls.enabled` | `PROXY_BACKEND_TLS_ENABLED` | false | TLS to the nodes |
| `proxy.backend.tls.ca-file` | `PROXY_BACKEND_TLS_CAFILE` | system trust store | CA to trust for the nodes' certificates |
| `proxy.backend.tls.insecure` | `PROXY_BACKEND_TLS_INSECURE` | false | Skip certificate verification |
