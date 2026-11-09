# MCP call sequences: streamable HTTP examples

The server is a long-lived MCP streamable-HTTP service, not a stdio process. By default it listens at `http://127.0.0.1:8089/mcp`. These examples show the JSON-RPC requests sent by an MCP client; in ordinary use, let the MCP client manage initialization, session headers, and request ids.

## Initialize and list tools

```sh
MCP_URL=http://127.0.0.1:8089/mcp
curl -sS -D headers -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"example","version":"1"}}}' \
  "$MCP_URL"

SESSION=$(grep -i '^mcp-session-id:' headers | tr -d '\r' | awk '{print $2}')
curl -sS -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$MCP_URL"

curl -sS -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' "$MCP_URL"
```

A response may be a JSON body or an SSE event containing a `data:` JSON-RPC payload. Keep the `Mcp-Session-Id` returned by initialization for subsequent requests in that session. The server advertises six tools: `search`, `list_records`, `select`, `crawl`, `rank`, and `zotero_sync`.

## Call a tool

Send `tools/call` to the same URL with the session header. The result is under `result.content`; the tool's text content is commonly JSON.

```sh
curl -sS -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search","arguments":{"query":"machine learning clinical safety","from":"2019-01-01","to":"2023-01-01","limit":10,"collect":true}}}' \
  "$MCP_URL"
```

## Repository and crawl workflow

```jsonc
// Search and add the results to the durable repository.
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search","arguments":{"query":"artificial intelligence clinical safety","limit":20,"collect":true}}}

// Optionally mark specific identifier references; this does not delete anything.
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"select","arguments":{"ids":[{"idType":"DOI","id":"10.1136/bmjqs-2018-008370"}],"value":true}}}

// Crawl down from explicit seeds. Use depth/maxRecords to bound the work.
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"crawl","arguments":{"action":"start","seeds":[{"idType":"DOI","id":"10.1136/bmjqs-2018-008370"}],"direction":"DOWN","depth":1,"maxRecords":100}}}

// Follow the background crawl by the job id returned from start.
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"crawl","arguments":{"action":"status","job":"<job-id>"}}}

// Review repository records and rank within this corpus.
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"rank","arguments":{"method":"page_rank","top":25}}}
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"list_records","arguments":{"filter":"selected","top":25,"style":"apa"}}}
```

`crawl` also accepts `fromSelection:true`, `direction` `UP` or `BOTH`, `waitSeconds`, and `action` `stop`. Its `status` action can omit `job` to inspect the most recent job. Only one crawl runs at a time.

## Zotero sync

```jsonc
// Check which selected records Zotero already holds before writing.
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"zotero_sync","arguments":{"action":"report"}}}

// Publish missing selected records to a new sub-collection, optionally with tags/PDFs.
{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"zotero_sync","arguments":{"action":"publish","collection":"AI safety review","tags":["literature-review"],"includePdf":false,"limit":25}}}
```

Publishing requires Zotero writes to be enabled and confined by the configured `zotero-write-collection`; it does not overwrite existing library items. `zotero_sync` also supports `action:"collections"`.

## Reference implementation

`scripts/mcp-smoke-test.sh` starts the packaged server on an ephemeral loopback port and performs a real streamable-HTTP handshake and offline tool calls. Use it as the executable protocol and packaging check.
