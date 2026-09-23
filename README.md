# Bibliographic API client (MCP server)

A Java MCP (Model Context Protocol) server exposing clients for various bibliographic APIs through a single JSON format, plus a plain-Java facade over the same functionality.

## Currently supported APIs
* Entrez APIs including PubMed: eSearch, eFetch and eLinks
* CrossRef API
* Unpaywall API
* Europe PMC
* Open Citations
* PMC IDConverter API
* Open access PDF retrieval and reference extraction including Cermine integration
* Zotero — read and annotate the user's curated library through the desktop client's local API

Design principles: respecting rate limits; multi-thread support; extensive caching of API results using EHCache; exception-free query results using Java 8 Optionals; arbitrarily large querying with batching to multiple API calls.

## Running the MCP server

Build the shaded jar:

```bash
mvn package -DskipTests
```

Register it in an MCP client (stdio transport). The first argument is the
secrets file path (CrossRef/Entrez/Unpaywall API keys; falls back to the
`BIBLIO_SECRETS_PATH` environment variable), the optional second argument is
the EHCache directory:

```json
{
  "mcpServers": {
    "bibliographic-api-client": {
      "command": "java",
      "args": [
        "-jar", "/path/to/target/bibliographic-api-client-master-SNAPSHOT.jar",
        "/path/to/.private/apis/secrets.prop",
        "/tmp/ehcache/biblio"
      ]
    }
  }
}
```

All logging goes to stderr; stdout is reserved for the protocol.

## Tools

Core (stateless per call):
* `search` — query crossref / entrez / europepmc with optional date range, limit and API filter
* `fetch` — full record details for a list of ids (DOI, PMID, PMCID...)
* `cites` / `cited_by` — citation graph queries
* `map_ids` — alternate id types via id mapping APIs
* `references_from_pdf` — download a pdf, extract and match its reference list
* `citation_styles` — supported citation styles

Session state (a long-lived repository held in memory):
* `collect_search`, `collect_search_by_api`, `add_records`, `remove_records` — mutate the session
* `fetch_missing` — fetch details for all ids lacking them
* `expand_citation_graph` — expand the citation graph up/down with a size limit
* `find_alternate_ids` — id mappings for all session records
* `session_status`, `session_records`, `session_citations` — inspect the session
* `save_repository`, `load_repository` — persist / restore a session

Analysis:
* `page_rank` — page rank over the session citation graph, joined with titles
* `topic_model` — LDA topic model over the session records (title + abstract)

Zotero (optional; local API of a running desktop client):

* `zotero_status`, `zotero_collections` — what library is on the other end
* `zotero_import` — bring a collection, a search or the library into the session
* `zotero_check` — which records the library already holds (DOI, ISBN, or
  normalised title+year)
* `zotero_publish` — create a sub-collection of the configured write target and add
  only the records not already held (≤25 per call); nothing existing is modified
* `zotero_tag` — union tags onto items inside the configured collection
* `zotero_cleanup_report` — compare every item in a collection against the APIs,
  field by field: agreements, gaps, conflicts, and items it could not check
* `zotero_apply_updates` — apply an explicit `{itemKey, fields}` change set to
  existing items, overwriting only those fields and reporting the old values

Writes are off by default; see [Zotero configuration](design/external-interfaces/interface-002-configuration-and-secrets.md).

## Plain-Java facade

The same functionality is available programmatically without MCP, via
`uk.co.terminological.bibliography.mcp.BiblioFacade` and `Session`:

```java
BiblioFacade facade = new BiblioFacade(secretsPath, cacheDir);
Session session = facade.getSession();
session.collectSearch("machine learning");
session.expandDownCitationGraph(60);
session.fetchMissingRecords();
Map<UUID, Double> rank = Analysis.pageRank(session);
```

## Smoke test

```bash
./scripts/mcp-smoke-test.sh
```
