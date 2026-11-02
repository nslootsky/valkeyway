# Contributing

Bug reports, fixes and improvements are welcome. For anything bigger than a small fix, open an issue first so we can agree on the approach before you spend time on it.

## Reporting bugs

Open an issue with:

- the ValkeyWay version (`HELLO` or `INFO server` shows it) and how you run it;
- the Valkey version and the cluster's shape (primaries, replicas);
- the client library, and the commands or steps that show the problem;
- what you expected, and what happened instead, with any errors from the proxy's log.

Report security vulnerabilities privately instead, as described in [SECURITY.md](SECURITY.md).

## Making a change

[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) covers building, running locally and the test suites. Before opening a pull request:

1. Run the unit and integration tests: `./mvnw verify -Dskip.integration.tests=false` (needs Docker or Podman).
2. Add tests for what you changed. A new kind of reply needs a case in `DifferentialRepliesTest`, which compares the proxy's replies with a node's.
3. Update the docs when behavior or settings change, and add a line to `CHANGELOG.md` for anything users would notice.
4. Sign off every commit (below).

Keep pull requests focused on one change; they're easier to review and to revert.

## Sign off your commits

ValkeyWay uses the [Developer Certificate of Origin](https://developercertificate.org/) (DCO). Signing off a commit states that you wrote the change, or otherwise have the right to submit it under the project's license.

Sign off by adding `-s` when you commit:

```bash
git commit -s -m "Fix the reply to HELLO without a protocol version"
```

This adds a line with the name and email from your git configuration:

```
Signed-off-by: Your Name <you@example.com>
```

A check on every pull request makes sure each commit is signed off by its author. If you forgot, sign off the commits already on your branch and push again:

```bash
git rebase --signoff main
git push --force-with-lease
```

## License

Contributions are released under the [MIT License](LICENSE), the same as the rest of the project.
