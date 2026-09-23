# MCP call sequences: raw JSON-RPC examples

Protocol details: the server speaks newline-delimited JSON-RPC 2.0 on
stdio. One JSON object per line on stdout, responses flushed per line.
Implemented methods: `initialize`, `ping`, `tools/list`, `tools/call`, and
JSON-RPC error responses for anything else. Notifications (no `id`) get no
response.

## Handshake

```jsonc
// client → server
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"client","version":"1"}}}
// server → client
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"bibliographic-api-client","version":"master-SNAPSHOT"}}}
// client → server (notification — no id, no response)
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

## Workflow A: literature search

```jsonc
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search","arguments":{"query":"machine learning clinical safety","from":"2019-01-01","to":"2023-01-01","limit":10}}}
// result.content[0].text is a JSON array of unified records:
// [{"id":"10.1136/bmjqs-2018-008370","idType":"DOI","title":"...","abstract":"...",
//   "journal":"...","date":"2019-03-01","PMID":"30636200","DOI":"10.1136/...","PMCID":"PMC6560460",
//   "publicationType":"article-journal","publisher":"BMJ",
//   "provenance":{"source":"crossref","nativeType":"journal-article"}}]
// publicationType uses the CSL vocabulary (article-journal, chapter, report, posted-content,
// book, dataset, ...) and is absent when the source did not say; ISBN appears for books.
// provenance records which API asserted the record and its native type for that API.

{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"fetch","arguments":{"ids":[{"idType":"DOI","id":"10.1136/bmjqs-2018-008370"},{"idType":"PMID","id":"30636200"}]}}}
```

## Workflow B: citation network

```jsonc
// 1. collect
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"collect_search","arguments":{"query":"artificial intelligence bias clinical safety"}}}
// result.content[0].text = "Records: 10\nIds: 22 (of which unique 19)\nCitation links: 8"

// 2. cross-map ids so records merge across schemes
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"find_alternate_ids","arguments":{}}}

// 3. complete details
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"fetch_missing","arguments":{}}}

// 4. expand — pass a limit; unbounded on hub papers
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"expand_citation_graph","arguments":{"direction":"up","limit":1000}}}
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"expand_citation_graph","arguments":{"direction":"down","limit":60}}}

// 5. inspect
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"session_citations","arguments":{}}}
// [{"sourceUuid":"...","targetUuid":"...","sourceTitle":"...","targetTitle":"..."}]

// 6. persist / restore (survives server restarts)
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"save_repository","arguments":{"path":"/tmp/biblio-session.ser"}}}
{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"load_repository","arguments":{"path":"/tmp/biblio-session.ser"}}}
```

## Workflow C: analysis

```jsonc
{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"page_rank","arguments":{}}}
// [{"uuid":"...","pageRank":0.00015,"title":"..."}]

{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"topic_model","arguments":{"topics":10,"alpha":0.1,"beta":0.1,"stopwords":["study","result"]}}}
// [{"topic":0,"uuid":"...","score":0.23}]
```

## Workflow D: PDF references

```jsonc
{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"references_from_pdf","arguments":{"url":"https://doi.org/10.5281/zenodo.14607839"}}}
// [{"id":"10.1109/EMBC.2016.7591355","idType":"DOI","title":"..."}] — matched CrossRef works
```

## Workflow E: id mapping

```jsonc
{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"map_ids","arguments":{"ids":[{"idType":"DOI","id":"10.1136/bmjqs-2018-008370"}]}}}
// CrossRef and Unpaywall are DOI-keyed (CrossRef has no pmid filter at all), so when a
// record is only known by PMID/PMCID, map_ids first and fetch by the DOI it returns.
// Europe PMC accepts all three id types directly.
// [{"sourceId":"10.1136/bmjqs-2018-008370","sourceIdType":"DOI","targetId":"30636200","targetIdType":"PMID"}]
```

## Errors and misc

```jsonc
// unknown method — JSON-RPC error
{"jsonrpc":"2.0","id":16,"method":"bogus"}
{"jsonrpc":"2.0","id":16,"error":{"code":-32601,"message":"Method not found: bogus"}}

// tool error — isError content (not a JSON-RPC error)
{"jsonrpc":"2.0","id":17,"method":"tools/call","params":{"name":"references_from_pdf","arguments":{"url":"notaurl"}}}
{"jsonrpc":"2.0","id":17,"result":{"content":[{"type":"text","text":"Internal error: ..."}],"isError":true}}

{"jsonrpc":"2.0","id":18,"method":"ping"}
{"jsonrpc":"2.0","id":18,"result":{}}

{"jsonrpc":"2.0","id":19,"method":"tools/list"} // all 21 tools with inputSchema
```

## Reference implementation

`scripts/mcp-smoke-test.sh` pipes a handshake + offline tool calls into the
jar and asserts the responses — use it as the reference implementation of a
protocol round trip.
