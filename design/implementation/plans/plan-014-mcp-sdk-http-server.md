---
class: plan
id: plan-014-mcp-sdk-http-server.md
title: Mcp sdk http server
status: draft
targetVersion: unassigned
tags:
- mcp
- transport
- http
- dependencies
HAS_FEATURE:
- feature:feat-010-crawl-engine-with-zotero-mirror.md
---
# Plan 014 — MCP SDK over HTTP

Replace the hand-rolled stdio server with the MCP Java SDK's streamable HTTP
transport on an embedded Jetty. The decision and its consequences are
[adr-006](../../../architecture/decision-records/adr-006-adopt-mcp-sdk-over-http.md); this plan is the mechanics.

## Why it is not first

The transport is the smallest of the four pieces of work in this release and the
one with the fewest unknowns, but it is deliberately **not** done first.
[plan-012](plan-012-workflow-tool-surface.md) deletes most of the 30 tools
that exist today, and porting them to the SDK before deleting them is work that
exists only to be thrown away. The order is:

1. [plan-010](plan-010-versioned-repository-store.md) — the store.
2. [plan-011](plan-011-background-crawl-engine.md) — the crawl engine.
3. this plan together with plan-012 — stand the SDK server up **on the new
   surface**, and delete `McpServer` and `McpTools` whole.

Until (3) the existing stdio server keeps working, so there is no window where
the project has no server.

What *is* done first is proving the plumbing: Java 17, the SDK and Jetty
resolving, and one tool answering a `tools/call` over HTTP. That is a spike, not
the server, and it is thrown away.

## Work items

1. **Dependencies and Java level.** `maven.compiler.source/target` to 17,
   `io.modelcontextprotocol.sdk:mcp`, `jetty-server`, `jetty-ee10-servlet`.
   Check the resolved tree for a second Jetty: 9.4 is already present
   transitively and the two namespaces (`javax.servlet` vs `jakarta.servlet`)
   must not be confused with each other.
2. **`McpHttpServer`.** Build `McpServer.sync(transportProvider)` with
   `serverInfo`, tool capabilities, and the tool specifications from the new
   surface. Jetty on `127.0.0.1:<mcp-port>`, context `/mcp`, the SDK's
   `DefaultServerTransportSecurityValidator` installed.
3. **Tool specifications.** Each tool is a `Tool` (name, description, JSON
   schema) plus a call handler. The schemas exist as JSON strings in `McpTools`
   today and can move across unchanged; the handlers are the same lambdas with a
   different signature. The SDK validates arguments against the schema before
   calling the handler — which deletes a layer of hand-written argument checking,
   and means a schema that lies now fails loudly rather than silently.
4. **Lifecycle.** The process no longer ends when a client disconnects. It needs
   a shutdown hook that stops Jetty, closes the store and checkpoints a running
   crawl.
5. **Delete** `McpServer`, the stdio loop, and the JSON-RPC error-code mapping
   that ADR-002 described. The SDK owns all of it.
6. **Smoke test.** `scripts/mcp-smoke-test.sh` pipes JSON-RPC into stdin, which
   stops existing. It becomes: start the server on an ephemeral port, `initialize`,
   `tools/list`, call the two tools that need no network, assert, stop. Keep the
   citation render check (issue-022) — it tests the packaging, not the transport.

## Open questions

- **Progress notifications.** The SDK supports them and a background crawl is
  the obvious consumer, so `crawl_status` polling could become a notification
  stream. Worth doing, but after the crawl engine exists and works; this plan
  only has to not preclude it.
- **One process, one repository.** A port means a second client, not a second
  server, but nothing yet stops someone starting two servers on one repository.
  That is plan-010's locking question and it gets sharper here.
- **Does the MCP client configuration change shape?** A URL rather than a
  command; whether anything still wants a spawnable jar is a question for
  whoever configures the client, not for this plan.

## Spike result — 2026-11-03

The plumbing is proven, on this project's dependency set and Java 17. A throwaway
`McpHttpSpike` built `HttpServletStreamableServerTransportProvider` on embedded
Jetty 12 with one echo tool, and a curl handshake gave:

```
initialize   -> protocolVersion 2025-06-18, Mcp-Session-Id issued
notifications/initialized -> 202
tools/list   -> SSE: the tool with its schema
tools/call   -> SSE: {"content":[{"type":"text","text":"echo: hello"}],"isError":false}
```

Three things worth carrying forward:

- **The JSON mapper resolves to `JacksonMcpJsonMapper` (Jackson 3)** through the
  SDK's `ServiceLoader`, with no configuration and no contact with this project's
  Jackson 2. ADR-006's isolation argument holds in practice.
- **Schema validation happens before the handler.** Calling the echo tool without
  its required argument returned
  `Tool (spike_echo) input validation failed: … required property 'text' not
  found` as a tool error. Every `args.get("x") == null` check in `McpTools` can
  go, and a schema that lies about its arguments now fails loudly.
- **Responses come back as SSE** (`event: message`) rather than as a plain JSON
  body, even for a simple call. The rewritten smoke test has to parse that, which
  is the one place the transport change is visible in the test tier.

Java 17 with the SDK and Jetty on the classpath: `mvn clean test` 232 tests,
0 failures. The spike is deleted; the server proper is built on the new surface.
