# Security policy

## Supported versions

Security fixes go into the latest release. Upgrade to it to get them.

| Version | Supported |
|---|---|
| 0.2.x | Yes |
| 0.1.x | No |

## Reporting a vulnerability

Report vulnerabilities privately, not in a public issue: on the repository's **Security** tab, choose **Report a vulnerability**. That opens a private advisory that only you and the maintainers can see.

Include:

- the ValkeyWay version and your configuration (with secrets removed);
- the steps or commands that show the problem;
- what an attacker could do with it.

You'll get an acknowledgement within 7 days. Once the problem is confirmed, we'll agree on a fix and a disclosure date with you, and credit you in the advisory unless you'd rather not be named.

Problems in Valkey or in Valkey GLIDE themselves belong to those projects: report them through [Valkey's](https://github.com/valkey-io/valkey/security) and [Valkey GLIDE's](https://github.com/valkey-io/valkey-glide/security) security policies.

## Running ValkeyWay securely

[docs/AUTH_AND_TLS.md](docs/AUTH_AND_TLS.md) covers client passwords, TLS on both sides, and hardening a deployment.
