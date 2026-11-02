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
- interface:interface-001-mcp-stdio-protocol.md
TESTED_BY:
- scripts:mcp-smoke-test.sh
- test:java/uk/co/terminological/bibliography/mcp/TestCitationStyleTool.java
- test:java/uk/co/terminological/bibliography/mcp/TestMcpToolSurface.java
---

[mcp-smoke-test.sh](/scripts/mcp-smoke-test.sh) pipes a handshake and
offline tool calls into the shaded jar and asserts the responses:

- `initialize` returns the expected `serverInfo`
- `tools/list` includes `search`, `page_rank`, `topic_model`
- `citation_styles` and `session_status` (the two tools that need no network)
  succeed
- an unknown method returns error `-32601`

It is the reference implementation of a protocol round trip, and the
verification gate for builds.

```bash
mvn package -DskipTests
./scripts/mcp-smoke-test.sh
```

Note: this is an offline protocol check. Record-level behaviour is covered by
the `main()`-based end-to-end tests in `src/test/java/.../pubmedclient/`,
which hit live APIs and are therefore not run by surefire.

