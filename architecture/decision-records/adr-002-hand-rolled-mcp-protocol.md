---
title: Hand rolled mcp protocol
status: deprecated
targetVersion: 0.1.0
---
# Hand-roll the MCP protocol rather than adopt an SDK

## Context

The MCP server needs to speak newline-delimited JSON-RPC 2.0 over stdio and
implement `initialize`, `ping`, `tools/list` and `tools/call`. Options were
the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`), a framework
integration (Spring AI / Quarkus MCP), or writing the protocol subset
directly.

Constraints: the project targets Java 11; Jackson is already a dependency; the
build must work against a local `~/.m2` (the remote ICM repository has an
expired certificate), so new transitive dependency trees are a real cost.

## Decision

Hand-roll the protocol in `McpServer` using Jackson, with tool definitions in
`McpTools`. No new dependencies.

## Rationale

* The required subset is small — a read/parse/dispatch loop of roughly 300
  lines — and the message shapes are stable and well documented.
* The official SDK requires a newer Java baseline and pulls a large dependency
  tree; framework integrations are heavier still.
* Keeping the dependency surface small preserves offline builds and matches
  the project's existing minimal-dependency style.


* Only the subset is implemented: no resources, prompts, subscriptions,
  `listChanged` notifications, or protocol-version negotiation beyond echoing
  the client's requested version. A client needing those features will not
  find them.
* Protocol revisions must be tracked manually rather than by dependency
  upgrade.
* The protocol's correctness rests on a hand-written smoke test
  ([test-001](/design/test-scripts/test-001-mcp-protocol-smoke-test.md)) rather
  than a conformance suite.

## Superseded — 0.2.0

Replaced by [adr-006](adr-006-adopt-mcp-sdk-over-http.md): the MCP Java SDK
over HTTP, with the hand-rolled loop and the stdio transport deleted.

The reasoning here was not wrong when it was written; its premises expired. The
server became a background service rather than a per-conversation front-end
(feat-010), and the two costs this record weighed — a large dependency tree and
an offline `~/.m2` — fell when the jar stopped being shaded in 0.1.0 and the
build gained network access. The Java 11 constraint is simply overruled: the SDK
is compiled for 17.
