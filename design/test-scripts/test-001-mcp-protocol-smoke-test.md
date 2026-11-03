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
- test:java/uk/co/terminological/bibliography/mcp/TestCitationStyleTool.java
- test:java/uk/co/terminological/bibliography/mcp/TestMcpToolSurface.java
- scripts:RenderCheck.java
---

[mcp-smoke-test.sh](/scripts/mcp-smoke-test.sh) pipes a handshake and
offline tool calls into the built jar and asserts the responses:

- `initialize` returns the expected `serverInfo`
- `tools/list` includes `search`, `page_rank`, `topic_model`, and
  `citation_style` — a style has to be selectable for a citation to be rendered
- `citation_styles` and `session_status` (the two tools that need no network)
  succeed
- an unknown method returns error `-32601`

It then runs [RenderCheck.java](/scripts/RenderCheck.java) on the built jar's
classpath, which renders one citation from a literal record. That check belongs
here rather than in surefire because it is the *packaging* it tests: citation
rendering goes through a javascript engine whose language registrations live in
dependency resources, and surefire runs against the separate dependency jars
whatever the build packages — so a unit test passes while the artefact a user
runs fails, which is what
[issue-022](/design/implementation/issues/issue-022-shaded-jar-cannot-render-citations.md)
was.

It is the reference implementation of a protocol round trip, and the
verification gate for builds.

```bash
mvn package -DskipTests
./scripts/mcp-smoke-test.sh
```

Note: this is an offline protocol check. Record-level behaviour is covered by
the `main()`-based end-to-end tests in `src/test/java/.../pubmedclient/`,
which hit live APIs and are therefore not run by surefire.

