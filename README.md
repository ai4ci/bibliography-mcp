# Bibliographic API client (MCP server)

A Java MCP (Model Context Protocol) server exposing clients for various bibliographic APIs through a single JSON format, plus a plain-Java facade over the same functionality.

## Currently supported APIs
* Entrez APIs including PubMed: eSearch, eFetch and eLinks
* CrossRef API
* OpenAlex — citation edges both ways, identifier mapping, open-access pdf location, and metered search (see below)
* Europe PMC
* Open Citations
* PMC IDConverter API
* Open access PDF retrieval and reference extraction including Cermine integration
* medRxiv and bioRxiv — preprint metadata, their reference lists from the JATS full
  text, and which journal article a preprint became. Their own search interface is
  not reachable programmatically (bioRxiv serves a Cloudflare challenge), so
  preprints are searched through Europe PMC, which indexes both servers essentially
  completely — see
  [issue-015](design/implementation/issues/issue-015-preprint-html-search-is-blocked-by-cloudflare.md)
* Zotero — read and annotate the user's curated library through the desktop client's local API

Design principles: respecting rate limits; multi-thread support; extensive caching of API results using EHCache; exception-free query results using Java 8 Optionals; arbitrarily large querying with batching to multiple API calls.

## Running the MCP server

Build it:

```bash
mvn package -DskipTests
```

That produces `target/bibliographic-api-client-<version>.jar` and
`target/lib/`, which holds the dependencies. The jar's manifest names them by
relative path, so **`lib/` has to stay beside the jar** — copy both, or neither.
It is deliberately not a fat jar: merging the dependencies into one archive
silently dropped resources that two of them publish at the same path, which broke
citation rendering in the built server and nothing in the test suite could see it
([issue-022](design/implementation/issues/issue-022-shaded-jar-cannot-render-citations.md)).

Register it in an MCP client (stdio transport). The first argument is the
secrets file path (CrossRef/Entrez/OpenAlex API keys; falls back to the
`BIBLIO_SECRETS_PATH` environment variable), the optional second argument is
the EHCache directory:

```json
{
  "mcpServers": {
    "bibliographic-api-client": {
      "command": "java",
      "args": [
        "-jar", "/path/to/target/bibliographic-api-client-0.0.11-SNAPSHOT.jar",
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
* `search` — query crossref / entrez / europepmc / openalex with optional date range, limit and API filter
* `fetch` — full record details for a list of ids (DOI, PMID, PMCID...)
* `cites` / `cited_by` — citation graph queries
* `map_ids` — alternate id types via id mapping APIs
* `references_from_pdf` — download a pdf, extract and match its reference list
* `citation_styles` — list the supported citation styles
* `citation_style` — choose one; every record returned afterwards carries a rendered `citation`

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

### OpenAlex credits

OpenAlex is the one metered source: a lookup by identifier is free, a filtered call
costs 1 credit and a text search 10, against a daily allowance (10,000 with a key,
a tenth of that without). Searching is therefore gated — `openalex-daily-credits`
sets the ceiling and `openalex-search-credit-reserve` the part of it searching may
not touch, so a day of searches cannot leave the citation and fetch calls, which no
other source answers, unable to run. Below the reserve OpenAlex simply drops out of
`search` and the free sources answer; it is never the reason a search fails. See
[configuration](design/external-interfaces/interface-002-configuration-and-secrets.md).

## Plain-Java facade

The same functionality is available programmatically without MCP, via
`uk.co.terminological.bibliography.api.BiblioFacade` and
`uk.co.terminological.bibliography.mcp.Session`:

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
