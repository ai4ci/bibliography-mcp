---
class: adr
id: adr-006-adopt-mcp-sdk-over-http.md
title: Adopt the MCP Java SDK and serve over HTTP
status: final
targetVersion: unassigned
tags:
- mcp
- protocol
- transport
- dependencies
---
# Adopt the MCP Java SDK, and serve over HTTP rather than stdio

Supersedes [adr-002](adr-002-hand-rolled-mcp-protocol.md).

## Context

ADR-002 hand-rolled the protocol: a read/parse/dispatch loop over
newline-delimited JSON-RPC on stdio, no new dependencies, Java 11. That was the
right call for what the server was then — a library front-end, started by a
client, living for one conversation.

Two things changed it.

**The server stopped being a front-end and became a service.** feat-010 makes
the repository durable and the citation crawl a background job that outlives any
one request. A process that holds a crawl for hours, that a second client might
want to query mid-crawl, and that is meaningless without the state it accumulated
is a service, and stdio is the wrong shape for one: the transport binds the
server's lifetime to one client's, and only one client can ever attach.

**The hand-rolled subset stopped being free.** ADR-002 listed what it did not
implement — resources, prompts, subscriptions, `listChanged`, version
negotiation. A background crawl wants exactly those: progress notifications
rather than a client polling `crawl_status`, and a tool list that can change as
a job starts and finishes.

## Decision

Depend on `io.modelcontextprotocol.sdk:mcp` and serve the streamable HTTP
transport from an embedded Jetty, bound to loopback. Delete the hand-rolled
`McpServer` loop and the stdio transport with it.

| | |
|---|---|
| SDK | `io.modelcontextprotocol.sdk:mcp:2.1.0` |
| transport | `HttpServletStreamableServerTransportProvider`, endpoint `/mcp` |
| container | embedded Jetty 12 (`jetty-server` + `jetty-ee10-servlet`) |
| JSON | the SDK's own Jackson 3 binding |
| Java | **17**, which is the SDK's floor |

## Rationale

- **The SDK's transports are the part worth not writing.** Streamable HTTP is
  session management, SSE streams, resumability and cancellation — far past the
  300 lines ADR-002 sized, and the part a hand-rolled server gets subtly wrong.
- **Java 17 is forced, not chosen.** `mcp-core` 2.1.0 ships class files at major
  version 61. ADR-002's Java 11 constraint is the one thing here that is simply
  overruled rather than re-argued.
- **The dependency cost is smaller than ADR-002 feared, and the build changed
  underneath it.** `mcp-core` pulls slf4j, jackson-annotations, reactor-core and
  a *provided* servlet API — not a framework. ADR-002's real constraint was an
  offline `~/.m2` and a shaded jar that merged everything into one archive; the
  jar stopped being shaded in 0.1.0 (issue-022), so dependencies now sit beside
  it in `lib/` and a wider tree costs little.
- **Jackson 3 keeps the migration honest.** The SDK's Jackson 2 binding wants
  `jackson-databind` 2.21.1 against this project's 2.9.7. The Jackson 3 binding
  lives under `tools.jackson.*` and cannot collide with `com.fasterxml.*`, so
  the transport change carries no Jackson risk. Upgrading this project's own
  Jackson is overdue and is its own task.
- **Jetty rather than Tomcat** is a coin-toss settled by size: two artifacts and
  no opinions about application structure.

## Consequences

- **stdio is gone.** A client can no longer spawn the jar and talk to it down a
  pipe; it connects to a running service. Any MCP client configuration that
  names a command has to become a URL.
- **The server binds to 127.0.0.1 and validates `Origin`/`Host`.** The SDK's
  HTTP transports do neither by default, and a browser on the same machine can
  otherwise reach a loopback port. This is the one piece of hardening that is not
  optional, because a service listening on a port is reachable in ways a pipe
  never was.
- **Java 11 consumers of the library are cut off.** The library half of this
  project is published as a jar someone else could depend on; 17 is now its
  floor too.
- **The smoke test changes subject.** `scripts/mcp-smoke-test.sh` pipes JSON-RPC
  into the jar's stdin, which stops existing. It becomes an HTTP exercise against
  a started server, and test-001 is rewritten with it.
- **Protocol revisions arrive by dependency upgrade**, which is the point, and
  bring their own breaking changes on the SDK's schedule rather than ours.
- **One process, one port, one repository.** Two servers on one repository is
  not addressed here and is plan-010's locking question.
