---
name: using-bibliographic-apis
description: 'Use this skill for literature search and citation-graph exploration with PubMed/Entrez, Crossref, Europe PMC, OpenAlex, OpenCitations, medRxiv or bioRxiv; or when using the bibliographic-api-client MCP server, its SQLite repository, crawl/rank tools, Zotero sync, or Java facade. Triggers: "search the literature", "find papers on", "citation network", "who cites this", "PubMed search", "Crossref", "Europe PMC", "OpenAlex", "OpenCitations", "map identifiers", "bibliographic API", "bibliographic-api-client". Not for general web search, arbitrary Zotero library edits, or non-bibliographic API work.'
license: MIT
metadata:
  author: Rob Challen
  project: bibliographic-api-client
---

# Using Bibliographic APIs

Use the bibliographic-api-client for API-backed literature search, citation-graph exploration, ranking, and a controlled sync of selected records to Zotero. The server exposes a durable SQLite-backed repository; it is not a stateless stdio process.

## Setup and connection

The server requires Java 17 or later. Build and packaging instructions are in the repository [README](../../README.md). The default endpoint is `http://127.0.0.1:8089/mcp` and the listener binds to loopback only. Register that URL as an MCP streamable-HTTP server; do not configure it as a command/stdio server. There is no authentication, so do not expose the listener beyond the local machine.

The server reads a Java properties file from its first command-line argument, otherwise `BIBLIO_SECRETS_PATH`, otherwise `~/.private/apis/secrets.prop`. The same file holds API keys and settings such as `mcp-port`, `repository-directory`, `repository-name`, `cache-directory`, and Zotero configuration. See [configuration and secrets](../../design/external-interfaces/interface-002-configuration-and-secrets.md). Logs go to stderr. Java callers can use `BiblioFacade`, `RepositoryStore`, and `CrawlEngine` directly; see the README for a code example.

Zotero is optional. Set `zotero-mode=off` to disable it. Writes are refused unless `zotero-allow-writes=true` and a `zotero-write-collection` scope are configured; existing Zotero items are not overwritten.

## Current MCP tools

The server registers these six tools (their exact schemas are returned by `tools/list`):

| Tool | Purpose |
|---|---|
| `search` | Search Crossref, Entrez, Europe PMC, and OpenAlex; results are ranked across APIs. Accepts text or an identifier, optional field/date/API filters, `limit`, `refresh`, `style`, and `collect`. |
| `list_records` | Read repository records, all or selected, optionally limited and rendered with a CSL style. |
| `select` | Mark records selected or unselected. This does not delete records or citation edges. |
| `crawl` | Start, inspect, or stop one background citation crawl. It can start from explicit identifier seeds or selected records. `DOWN` follows references; `UP` follows citing works; use `depth` and `maxRecords` to bound the crawl. |
| `rank` | Rank repository records with `page_rank`, `cited_by`, or the original search-score ranking `rrf`; these scores describe this repository, not all literature. |
| `zotero_sync` | List Zotero collections, report which selected records are already held, or publish missing selected records to a new sub-collection. Optional `includePdf` attaches an available open-access PDF. |

Do not rely on removed MCP tool names such as `fetch`, `collect_search`, `expand_citation_graph`, `map_ids`, `topic_model`, or `references_from_pdf`. Identifier lookup is available through `search`; citation expansion and ranking use the repository workflow below. `page_rank` remains a method value accepted by the `rank` tool.

## Typical workflow

1. Call `search` with a focused query and a reasonable `limit`. Use `apis` to restrict providers, `from`/`to` for publication dates, and `refresh=true` only when you need to bypass cached results. Set `collect=true` to put hits into the persistent repository; otherwise the results are returned without collection.
2. If useful, call `select` with the returned identifier references. Selection is reversible and does not remove rejected records from the graph.
3. Call `crawl` with `action="start"`, either explicit `seeds` or `fromSelection=true`. Start with `direction="DOWN"` for references; `UP` can be open-ended, so set a small `depth` and `maxRecords` unless the user asks for a broader crawl. The call returns a job id; use `action="status"` with that `job` to follow it, or set `waitSeconds` when starting to keep the request open briefly. One crawl runs at a time.
4. Use `rank` and `list_records` to inspect and review the repository. `rank` writes ranking scores back to the repository; `list_records` can return all records or only selected ones.
5. Before any Zotero write, use `zotero_sync` with `action="report"`. Publish only after confirming the destination collection and configured write scope; `action="publish"` requires a collection name and will not overwrite records Zotero already holds.

A crawl and its records persist in the SQLite repository across server restarts. The default repository is under `${XDG_DATA_HOME:-~/.local/share}/bibliographic-api-client/repositories/default/`; configure another directory/name for a separate corpus. The repository is durable user data, not the rebuildable HTTP cache.

## API coverage and guidance

- Search tools expose Crossref, Entrez, Europe PMC, and OpenAlex. OpenCitations and provider-specific clients also contribute citation links and record details during crawling.
- Europe PMC indexes medRxiv and bioRxiv preprints. The project does not provide direct preprint full-text search as separate MCP tools.
- OpenAlex has a metered credit budget. Searches may be skipped when they would consume the configured reserve; the remaining providers still answer. See the configuration document for `openalex-apikey`, `openalex-daily-credits`, and `openalex-search-credit-reserve`.
- Records from different APIs can have different fields. Treat identifiers and source provenance as evidence, and do not assume every result has a complete abstract or publication date.
- API calls are rate-limited and crawls can take time. A slow crawl is not necessarily stuck; inspect `crawl` status and per-API counts before retrying and spending more requests.

## Validation and troubleshooting

From the repository root, run `./scripts/mcp-smoke-test.sh` after a build. It starts the actual HTTP server with a temporary repository, checks the tool list and offline tool round-trips, and verifies citation rendering from the packaged runtime.

- **No results or API errors:** check the secrets path and provider-specific keys/settings; inspect stderr for the failing source.
- **Cannot connect:** confirm the process is running, the configured `mcp-port` is free, and the MCP client uses the `/mcp` URL over streamable HTTP.
- **Zotero call fails:** ensure Zotero desktop is running and local API access is enabled. For publishing, also configure write permission and a write collection scope.
- **Unknown tool:** check `tools/list`; only the six tools above are provided.

## References

- [MCP streamable-HTTP call examples](references/mcp-call-sequences.md)
- [Repository README](../../README.md)
- [Configuration and secrets](../../design/external-interfaces/interface-002-configuration-and-secrets.md)
- OpenCitations Index v2: https://api.opencitations.net/index/v2
- OpenCitations Meta: https://api.opencitations.net/meta/v1
- NCBI Entrez: https://www.ncbi.nlm.nih.gov/books/NBK25499/
