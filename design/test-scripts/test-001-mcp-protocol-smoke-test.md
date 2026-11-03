---
title: Mcp protocol smoke test
status: final
targetVersion: 0.1.0
tags:
- mcp
- smoke-test
HAS_FEATURE:
- feature:feat-001-expose-bibliographic-apis-over-mcp.md
HAS_INTERFACE:
- interface:interface-001-mcp-http-transport.md
TESTED_BY:
- scripts:mcp-smoke-test.sh
- scripts:RenderCheck.java
---

[mcp-smoke-test.sh](/scripts/mcp-smoke-test.sh) starts the built jar on an
ephemeral port with a temporary repository and Zotero off, drives a real
streamable-HTTP handshake against it, and asserts:

- `initialize` returns the expected `serverInfo` and a protocol version
- `tools/list` is **exactly** the six tools — a surface that drifts fails here
- `list_records` on an empty repository returns `[]` rather than an error
- `crawl status` with no crawl says so rather than failing

It then runs [RenderCheck.java](/scripts/RenderCheck.java) on the built jar's
classpath, which renders one citation from a literal record. That check belongs
here rather than in surefire because it is the *packaging* it tests: rendering
goes through a javascript engine whose language registrations live in dependency
resources, and surefire runs against the separate dependency jars whatever the
build packages — so a unit test passes while the artefact a user runs fails,
which is what [issue-022](issue:issue-022-shaded-jar-cannot-render-citations.md)
was.

```bash
mvn package -DskipTests
./scripts/mcp-smoke-test.sh
```

It is the reference implementation of a protocol round trip and the verification
gate for builds. The transport-level counterpart in the test tier is
`TestMcpHttpServer`, which drives the same server with the SDK's own client.

Note: this is an offline check. The tools it calls read the repository and never
an API; record-level behaviour against live APIs is the `@Category(LiveTest)`
tier.
