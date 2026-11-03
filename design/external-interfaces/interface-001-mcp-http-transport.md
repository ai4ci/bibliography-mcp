---
title: Mcp http transport
status: final
targetVersion: unassigned
tags:
- mcp
- protocol
HAS_FEATURE:
- feature:feat-001-expose-bibliographic-apis-over-mcp.md
- feature:feat-002-explore-citation-networks-in-a-session.md
- feature:feat-003-analyse-citation-graphs-and-topics.md
HAS_TEST:
- testscript:test-001-mcp-protocol-smoke-test.md
- testscript:test-002-api-behaviour-tests.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/mcp/McpHttpServer.java
- main:java/uk/co/terminological/bibliography/mcp/BiblioTools.java
---
# MCP over streamable HTTP

> **Not yet built.** This describes the transport as of the crawl-engine
> release; 0.1.0 shipped newline-delimited JSON-RPC over stdio, which
> [adr-006](adr:adr-006-adopt-mcp-sdk-over-http.md) replaces. The record of what
> stdio did is in that ADR and in this file's history.

The server is a long-lived process listening on a loopback port, not a command a
client spawns. A client connects to it; several can. The protocol is MCP's
streamable HTTP transport, served by the
[MCP Java SDK](../../architecture/decision-records/adr-006-adopt-mcp-sdk-over-http.md) from an embedded Jetty.

| | |
|---|---|
| endpoint | `POST`/`GET` `http://127.0.0.1:<port>/mcp` |
| transport | streamable HTTP (`HttpServletStreamableServerTransportProvider`) |
| bind address | `127.0.0.1` only |
| logging | stderr; stdout is no longer reserved for anything |

## Why a port rather than a pipe

The repository is durable and a citation crawl is a background job that outlives
the request that started it ([feat-010](../features/feat-010-crawl-engine-with-zotero-mirror.md)).
stdio ties the server's lifetime to one client's and admits exactly one client,
so a crawl could not be watched from anywhere except the conversation that
started it, and could not outlive it. See
[adr-006](../../architecture/decision-records/adr-006-adopt-mcp-sdk-over-http.md), which supersedes the
hand-rolled stdio protocol.

## What the SDK gives that the hand-rolled loop did not

Protocol version negotiation, session management, resumable streams, request
cancellation, `listChanged` notifications and progress notifications. The last
two matter here: a crawl reports progress by notification rather than by the
client polling `crawl_status` in a loop.

## Methods

The SDK implements the protocol; this project implements tools. The method set
is therefore whatever the SDK's version supports, rather than the five methods
the hand-rolled server recognised. What this project defines is the **tool
surface** — see [plan-012](../implementation/plans/plan-012-workflow-tool-surface.md).

## Following a crawl

A crawl outlives the call that starts it, and **a progress notification belongs
to a request that is still in flight**. Once `crawl start` has returned there is
no stream to put one on: measured, notifications sent after the response are not
delivered, with or without the client holding the standalone stream open. So
there are two ways to follow a crawl and they are different shapes:

| | |
|---|---|
| `crawl start` then `crawl status` | returns at once; the client polls. The background case, and what a long crawl wants |
| `crawl start` with `waitSeconds` and a `progressToken` | the call stays open that long and progress arrives as it happens, ending with the finished job |

A client that wants the notifications must send a `progressToken` — without one
there is nothing to address them to and none are sent — and must hold the
standalone stream (`openConnectionOnStartup`) if it also wants anything sent
outside a call.

A crawl that finishes *inside* the call is simply answered by the response,
which is why the offline tier asserts the result rather than a notification.

## Security

An HTTP transport is reachable in ways a pipe is not, and the SDK validates
neither `Origin` nor `Host` by default — a page in a browser on the same machine
can otherwise post to a loopback port. Both are required here:

- bind to `127.0.0.1`, never `0.0.0.0`;
- install the SDK's `DefaultServerTransportSecurityValidator` so `Origin` and
  `Host` are checked.

There is no authentication. The port is a local service for the user who started
it, and the threat it defends against is a web page in the same browser, not
another user on the machine. A shared or remote deployment would need more than
this interface describes.

## Configuration

| key | default | meaning |
|-----|---------|---------|
| `mcp-port` | `8089` | the loopback port to listen on; `0` takes whatever is free |
| `repository-directory` | `${XDG_DATA_HOME:-~/.local/share}/bibliographic-api-client/repositories/` | the parent of the repositories, per [adr-005](adr:adr-005-repository-uses-xdg-data-home.md) |
| `repository-name` | `default` | which repository under that parent this server serves |

Worked examples for every workflow are in
[skills/using-bibliographic-apis/references/mcp-call-sequences.md](/skills/using-bibliographic-apis/references/mcp-call-sequences.md),
which describes tool sequences rather than wire format and survives the
transport change.
