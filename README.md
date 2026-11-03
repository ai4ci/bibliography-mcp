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

The server is a **long-lived local service**, not a command a client spawns. It
holds a durable repository and a citation crawl that outlives the request that
started it, so it listens on a loopback port and speaks MCP over streamable HTTP
([ADR-006](architecture/decision-records/adr-006-adopt-mcp-sdk-over-http.md)):

```bash
java -jar target/bibliographic-api-client-0.2.0-SNAPSHOT.jar \
     ~/.private/apis/secrets.prop
# MCP server listening on http://127.0.0.1:8089/mcp
```

Register that URL in an MCP client:

```json
{
  "mcpServers": {
    "bibliographic-api-client": {
      "url": "http://127.0.0.1:8089/mcp"
    }
  }
}
```

The same properties file carries the API credentials, the Zotero settings,
`mcp-port`, and where the repository lives — see
[configuration](design/external-interfaces/interface-002-configuration-and-secrets.md).
The listener binds to `127.0.0.1` only. There is no authentication: it is a
service for the user who started it.

**stdio is gone.** Until 0.1.0 the server was a hand-rolled JSON-RPC loop over a
pipe; a client that spawned the jar now has to connect to a running one instead.

## Tools

Six, each a step of a workflow rather than a library call:

| tool | does |
|---|---|
| `search` | search crossref / entrez / europepmc / openalex, ranked across them, each record carrying the score that ordered it and the APIs that found it. An identifier is looked up rather than text-matched. `collect` adds the results to the repository |
| `list_records` | read the repository, everything or only what is selected; name a CSL style and each record carries a rendered citation |
| `select` | mark records. A mark, never a delete: what is rejected stays in the citation graph so the metrics stay honest |
| `crawl` | `start` a background citation crawl from seeds or the selection, ask its `status`, or `stop` it. It survives a restart |
| `rank` | `page_rank`, `cited_by` or `rrf` over what the repository holds — "in this corpus", not "in the literature" |
| `zotero_sync` | list `collections`, `report` what the library already holds, or `publish` the selection into a new sub-collection |

Everything the deleted tools did is still in the library: `BiblioFacade` and the
`client/` SPI keep `fetch`, `cites`, `cited_by` and `map_ids`. Deleting a tool is
not deleting a capability; it is refusing to make the model schedule it.

The repository is a SQLite file under
`${XDG_DATA_HOME:-~/.local/share}/bibliographic-api-client/repositories/`, and a
crawl writes into it as it goes.

## Plain-Java facade

The same functionality is available programmatically without MCP, via
`BiblioFacade`, `RepositoryStore` and `CrawlEngine`:

```java
BiblioFacade facade = new BiblioFacade(secretsPath, cacheDir);
try (RepositoryStore store = SqliteRepositoryStore.open(repositoryPath);
     CrawlEngine engine = new CrawlEngine(store, facade)) {

    facade.scoredSearch(ImmutableQuery.builder()
            .allFields("machine learning")
            .limit(25)
            .build())
        .forEach(hit -> {
            store.putRecord(hit.record());
            store.setScore(hit.record(), hit.score());
        });

    CrawlJob job = engine.start(seeds, Direction.DOWN, 2, 5000);
    // … returns immediately; job.getStatus() while it runs
    Map<ImmutableRecordReference, Double> rank = Ranking.pageRank(store);
}
```

`Session` survives as an in-memory helper for the LDA topic model, and no longer
serialises itself: a repository that outlives the process is the store's job.

## Smoke test

```bash
./scripts/mcp-smoke-test.sh
```
