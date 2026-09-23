---
status: final
target-version: 0.1.0
tags: mcp, protocol
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

# MCP stdio protocol

Newline-delimited JSON-RPC 2.0 over stdin/stdout. Logging goes to stderr so
stdout carries protocol only.

## Methods

| Method | Notes |
|--------|-------|
| `initialize` | returns `protocolVersion` (echoes the client's request), `capabilities.tools`, `serverInfo` |
| `notifications/initialized` | notification — no `id`, no response |
| `ping` | returns `{}` |
| `tools/list` | returns all tools with `name`, `description`, `inputSchema` |
| `tools/call` | `params.name` + `params.arguments`; returns `content[].text` and `isError` |

Method subsets beyond this (resources, prompts, subscriptions, `listChanged`)
are not implemented — see [ADR-002](/architecture/decision-records/adr-002-hand-rolled-mcp-protocol.md).

## Message shapes

Request:

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search","arguments":{"query":"..."}}}
```

Success — the payload is a JSON string in `content[0].text` (a plain string
for status-returning tools):

```json
{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"[...]"}],"isError":false}}
```

Tool failure — still a JSON-RPC result, flagged `isError`:

```json
{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Internal error: ..."}],"isError":true}}
```

Protocol failure — JSON-RPC error object:

```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found: bogus"}}
```

## Error codes

| Code | When |
|------|------|
| `-32700` | unparseable line |
| `-32600` | not a JSON object / no method |
| `-32601` | unknown method |
| `-32602` | unknown tool name |
| `-32603` | tool handler threw |

Worked examples for every workflow are in
[skills/using-bibliographic-apis/references/mcp-call-sequences.md](/skills/using-bibliographic-apis/references/mcp-call-sequences.md).

## Implemented by

- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/McpServer.java)
- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/McpTools.java)

## Features

- [HAS_FEATURE](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md)
- [HAS_FEATURE](/design/features/feat-002-explore-citation-networks-in-a-session.md)
- [HAS_FEATURE](/design/features/feat-003-analyse-citation-graphs-and-topics.md)
- [HAS_TEST](/design/test-scripts/test-001-mcp-protocol-smoke-test.md)
- [HAS_TEST](/design/test-scripts/test-002-api-behaviour-tests.md)
