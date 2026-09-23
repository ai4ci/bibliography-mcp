---
name: using-bibliographic-apis
description: 'Use this skill when searching PubMed, CrossRef, Europe PMC, Unpaywall or OpenCitations for literature; fetching bibliographic records by DOI/PMID/PMCID; exploring citation networks (cites/cited-by); mapping ids between schemes; extracting references from PDFs; bibliometric analysis (page rank, LDA topic models); or when working with the bibliographic-api-client MCP server or its plain-Java facade (BiblioFacade/Session). Triggers: "search the literature", "find papers on", "citation network", "who cites this", "pubmed search", "crossref", "europepmc", "unpaywall", "opencitations", "map pmid to doi", "references from pdf", "biblioapi", "bibliographic apis". Do NOT use for Zotero library management, general web search, or non-bibliographic API work.'
license: MIT
metadata:
  author: Rob Challen
  project: bibliographic-api-client
---

# Using Bibliographic APIs

A capability skill for agents using the bibliographic-api-client: a Java MCP
server exposing clients for multiple bibliographic APIs behind a unified
record format, plus a plain-Java facade over the same functionality.

## Scope

Supported APIs and what they are used for:

| API | Use |
|-----|-----|
| Entrez (PubMed) | eSearch / eFetch / eLinks — searching, record details, citation links, id mapping |
| CrossRef | searching, record details, citation matching, DOI metadata |
| Europe PMC | searching, record details, references, citing articles |
| Unpaywall | open-access status and PDF locations |
| OpenCitations (Index v2) | citation network links (cites / cited-by) |
| PMC IDConverter | mapping between PMID / PMCID / DOI |
| Cermine (PDF) | downloading PDFs and extracting reference lists |

The server wraps these in two layers:

- **Stateless tools** — one call = one set of API queries.
- **Session tools** — a long-lived in-memory repository (the `Session`):
  collect results, merge and deduplicate records across id schemes, expand
  the citation graph, persist/restore. This is the right shape for
  multi-step literature exploration.

## Prerequisites and installation

Installation details are **not stable** — the project is expected to migrate
to another repository, and the jar name/version will change with it. If a
path below does not exist, go to the repository root and check the README for
the current build command and jar location rather than guessing.

To build from the repository root (Maven, Java 11+):

```bash
mvn package -DskipTests
# produces target/bibliographic-api-client-<version>.jar — a shaded fat jar
```

To verify an existing jar is a working MCP server, run the smoke test from
the repository root:

```bash
./scripts/mcp-smoke-test.sh [path-to-jar] [secrets-path]
```

## Setup: API keys

The server needs a Java properties file with API credentials. By default it
looks for `~/.private/apis/secrets.prop`, or the `BIBLIO_SECRETS_PATH`
environment variable, or takes the path as its first CLI argument (second
argument: EHCache directory).

Required properties:

```properties
app-id=<identifies your application to the APIs>
developer-email=<your email; CrossRef/EuropePMC/Unpaywall use it for polite-pool access>
crossref-clickthroughtoken=<CrossRef access token>
pubmed-apikey=<NCBI API key>
```

The email and app id matter even without paid keys: CrossRef and EuropePMC
give better rate limits to requests identifying themselves (polite pool).
NEVER commit secrets files, and NEVER write their contents into documents.

Registration in an MCP client (stdio transport) — adjust jar/secrets paths
for the current install:

```json
{
  "mcpServers": {
    "bibliographic-api-client": {
      "command": "java",
      "args": [
        "-jar", "/path/to/bibliographic-api-client-<version>.jar",
        "/path/to/.private/apis/secrets.prop",
        "/tmp/ehcache/biblio"
      ]
    }
  }
}
```

All logging goes to stderr; stdout is reserved for the protocol. Never parse
the server's stderr as output — use it only for diagnostics.

## Use cases and MCP call sequences

Protocol: newline-delimited JSON-RPC 2.0. Always initialize first
(`notifications/initialized` is a notification: no `id`, no response).
The sequences below cover the common workflows; detailed raw JSON-RPC
examples live in [references/mcp-call-sequences.md](references/mcp-call-sequences.md).

### Workflow A: literature search (stateless)

`initialize` → `notifications/initialized` → one `tools/call`:

- `search` with `query` (plus optional `from`/`to` dates `YYYY-MM-DD`,
  `limit`, `apis` filter). Returns unified records (id, idType, title,
  abstract, journal, date, PMID, DOI, PMCID, ISBN, url, publicationType,
  publisher, provenance). `publicationType` is a CSL type
  (`article-journal`, `chapter`, `report`, `posted-content`, `book`, ...) and is
  absent when the source did not state one, so treat it as a hint rather than a
  guarantee; `provenance.source` says which API the record came from.
- `fetch` with `ids: [{idType, id}]` for full details of known records.

Use when the agent just needs search results — no session state involved.

### Workflow B: citation network exploration (stateful)

The repository pattern: collect → complete → expand → analyse.

1. `collect_search` (or `collect_search_by_api` / `add_records`) — collect
   hits into the session.
2. `find_alternate_ids` — cross-map PMID/DOI/PMCID so records merge correctly.
3. `fetch_missing` — fetch details for collected ids lacking them.
4. `expand_citation_graph` with `direction: "up"` (articles citing) or
   `"down"` (references cited) and a `limit` — pulls citation links and new
   records into the session. Always pass a limit: expansion size is
   unbounded on hub papers.
5. `session_records` / `session_citations` / `session_status` — inspect.
6. `save_repository` with a `path` — persist the session; `load_repository`
   restores it later (survives server restarts).

Use for "map the literature around X", "who cites X", building citation
graphs for analysis. Rate limits apply per API (OpenCitations: 180
requests/min) — expansion of large graphs takes minutes, which is expected.

### Workflow C: bibliometric analysis (stateful)

After workflow B has collected a session:

- `page_rank` — page rank over the session citation graph, joined with titles.
- `topic_model` — LDA over session records (title + abstract); optional
  `stopwords`, `topics`, `alpha`, `beta`. Slow on large sessions by design;
  prefer smaller sessions or fewer topics.

### Workflow D: PDF reference extraction (stateless)

`references_from_pdf` with a `url` — downloads the PDF, extracts its
reference list (Cermine) and matches references to CrossRef works. Slow
(requests hit CrossRef per reference string); use for a single PDF, not bulk.

### Workflow F: the curated Zotero library (the snowball workflow)

The point of the Zotero tools: *"here is a collection I built; find related work
and add the best of it into a sub-collection"*.

1. `zotero_status` — is the local API reachable, and are writes enabled? Writes are
   off by default and confined to one configured collection.
2. `zotero_collections` — find the seed collection's key.
3. `zotero_import` with that `collection` key — the seed records enter the session
   (they bring no citation links; Zotero has no citation graph).
4. Expand and rank as in workflows B and C: `expand_citation_graph`, `page_rank`,
   `topic_model`. Weigh the scores yourself, with the user.
5. `zotero_check` — which candidates the library already holds. Matching is DOI,
   then ISBN, then normalised title+year, so it works for the ~47% of the library
   with no DOI. **Check before publishing**: it is what stops a duplicate flood.
6. `zotero_publish` with a `subcollection` name (and `tags`) — creates the
   sub-collection under the configured target and adds only the records that are
   not already held, at most 25 per call. It reports what it held back and what it
   left for a next call.
7. `zotero_tag` — record why items were added, for items inside the configured
   collection. Tags are unioned, never replaced.

Nothing in the library is ever overwritten with API data. When the API disagrees
with an item already held, the item is skipped and the choice (update it, or add a
duplicate and let Zotero's duplicate detection resolve it) belongs to the agent
and the user — see the write rules in the feature's design record.

### Workflow G: clean up a collection's metadata

The user opens a collection and asks for the metadata to be tidied.

1. `zotero_cleanup_report` with the `collection` key. Per field it gives the Zotero
   value, what each API says (attributed by source), and a class: `agree`,
   `fillable` (Zotero is empty and an API has a value), `conflict` (both have
   values and they differ), or `zotero-only`. Items with no identifier, or that no
   API knows, are reported as such rather than skipped quietly.
2. **You decide, not the tool.** Field names in the report are the ones Zotero
   uses, so a value can be copied straight into an update. Where an API offers
   something the item lacks, that is usually worth taking without asking. Where
   both sides have a value, judge whether it is substance or precision — `2019`
   against `2019-01-01` is a judgement call, and the user may want to be asked.
   Markup is already stripped from the values the report offers, so you are
   comparing content — and where the *Zotero* value holds markup (JATS pasted in by
   a publisher-page import, say) the field carries `zoteroHasMarkup`: the values
   agree, but the library still holds the mess, and the API value on offer is the
   clean one to write back.
3. `zotero_apply_updates` with the change set you decided on — only the named
   fields are written, and the previous values are reported back so the change is
   reviewable. Writes are off unless configured, and confined to one collection.
4. If a difference cannot be resolved, prefer `zotero_publish` with `includeHeld`
   to add the API version as a duplicate: Zotero's own duplicate view is where the
   user can then choose between them.

### Workflow E: id mapping (stateless)

`map_ids` with `ids` — alternate id types via the PMC IDConverter.
Session-wide equivalent: `find_alternate_ids`.

## Guidance

- Prefer the session workflows (B/C) over repeated stateless calls when
  exploring — the session merges records across APIs and caches, so fewer
  duplicate API hits.
- `publicationType` distinguishes kinds of work (a chapter is not a journal
  article); where it matters, prefer records whose `provenance.source` you trust.
- **medRxiv/bioRxiv search by date, not by words.** `search` includes preprints, but
  that API lists what was posted in a window: with no dates it uses the last 30 days,
  every word of the query must appear in the title or abstract, and a busy window is
  scanned only as far as the page budget (the log says how much was covered). Date
  your queries, and use `map_ids` to find out which journal article a preprint became.
- **CrossRef and Unpaywall are DOI-keyed.** CrossRef has no `pmid` filter at all, so
  if a record is only known by PMID/PMCID, call `map_ids` first and fetch by the DOI
  it returns — or fetch through Europe PMC, which accepts all three id types.
  Otherwise those sources silently contribute nothing to the merged record.
- Records use a unified format regardless of source API. Treat `id`/`idType`
  as the canonical reference; `PMID`/`DOI`/`PMCID` are convenience lookups.
- Dates are `YYYY-MM-DD`; `limit` caps results per API.
- Never assume a record has all fields — each API populates different
  subsets; Optionals are rendered as absent fields.
- For raw JSON-RPC inspection, the smoke test script is the reference
  implementation of a protocol round trip.

## Validation

- After building: `./scripts/mcp-smoke-test.sh` — must print PASSED.
- After registering in a client: `session_status` returns "Records: 0..." —
  a full protocol + tool round trip.
- After a workflow: `session_status` should show growing record/id/link
  counts; empty expansions on a hub paper suggest an API drift (see below).

## Troubleshooting

- **"Read timed out" on opencitations**: that API responds in 3-6s and is
  rate-limited to 180 req/min; the client already allows 30s. Slow is normal
  for large graphs — check `session_status` to confirm progress.
- **301/endpoint drift**: bibliographic APIs move endpoints (PMC idconv and
  OpenCitations both did). Symptoms: "Could not parse API result", or empty
  results from one API only. Fix: check the API's docs page, update the
  client's baseUrl/contract — the pattern is usually a baseUrl move plus
  small JSON shape changes.
- **"Could not create api client" / empty results everywhere**: secrets file
  missing or wrong path — check `BIBLIO_SECRETS_PATH` or the CLI argument.
- **Unknown tool / Method not found**: the server only implements
  `initialize`, `ping`, `tools/list`, `tools/call` — no resources/prompts.
- **Flooded logs**: logs go to stderr and are verbose at DEBUG; grep stderr
  for "Calling url" to see actual API traffic.

## References

- [references/mcp-call-sequences.md](references/mcp-call-sequences.md) — raw
  JSON-RPC examples for each workflow
- Repository README — supported APIs, current build, tool list
- OpenCitations Index v2 docs: https://api.opencitations.net/index/v2
- OpenCitations Meta docs: https://api.opencitations.net/meta/v1
- NCBI Entrez docs: https://www.ncbi.nlm.nih.gov/books/NBK25499/
