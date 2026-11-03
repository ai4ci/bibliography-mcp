---
title: Crawl engine with Zotero mirror
status: draft
targetVersion: unassigned
tags:
- mcp
- citations
- crawl
- zotero
- persistence
HAS_PLAN:
- plan:plan-010-versioned-repository-store.md
- plan:plan-011-background-crawl-engine.md
- plan:plan-012-workflow-tool-surface.md
HAS_INTERFACE:
- interface:interface-001-mcp-http-transport.md
HAS_PROTOTYPE:
- prototype:prototype-003-zotero-attachment-paths.md
HAS_TEST:
- testscript:test-001-mcp-protocol-smoke-test.md
---
## Why this feature exists

The MCP server today is a bag of 29 primitives. Everything is a synchronous
call against one in-memory `Session`, so the agent has to sequence the whole
workflow correctly by itself: `collect_search`, then `fetch_missing`, then
`find_alternate_ids`, then `expand_citation_graph` up, then down, then
`page_rank`, then decide, then `zotero_publish`. Each of those is a round trip
through the model's context, and a mistake in the ordering is invisible until
the result looks wrong. That is the "lot of workflow to do what it really
needs" problem.

It also overlaps Zotero in the wrong places. Browsing a library, listing
collections, creating items, tagging — Zotero does all of that, and generic
Zotero MCP servers already wrap it. What nobody else has is the engine:
multi-API dedupe with RRF, `MergePolicy` provenance-aware merging, id-mapping
across DOI/PMID/PMCID, and citation-graph expansion with per-client direction
conventions. The overlap should end, and the way for it to end is for this
project to stop being a second library front-end and become the crawl and
enrichment engine, with Zotero as the human-facing view of the result.

The seven workflows this has to support, in the words of the developer:

1. Set up a search, identify high-value hits, create a repository from them.
2. Create or sync a Zotero collection with the repository, updating and
   improving metadata, and enhancing local Zotero with discovered PDFs.
3. Start a depth-restricted citation crawl from the main items in a repository,
   in the background.
4. Report on crawl status.
5. Analyse crawl results for pagerank-style metrics.
6. Select high-relevance results out of a crawl.
7. Sync that back to Zotero.

## Current state, per workflow

Measured against `mcp/McpTools.java` (29 tools), `mcp/Session.java`,
`mcp/Analysis.java`, `api/BiblioFacade.java` and
`api/zotero/ZoteroClient.java`.

| workflow | what exists | what is missing |
|---|---|---|
| 1 search → repo | `search`, `collect_search`, `session_records`, RRF dedupe in `BiblioFacade` | `collect_search` builds `ImmutableQuery.builder().allFields(str)` so structured queries (fields, date window) never reach an API, made worse by `GenericSearchBuilder.withQuery` being a no-op ([issue-017](/design/implementation/issues/issue-017-generic-search-builder-withquery-noop.md)). The RRF score is computed then thrown away — `recordJson` has no score field. "High value" has no signal at search time. |
| 2 Zotero + PDFs | `zotero_check`, `zotero_cleanup_report`, `zotero_apply_updates`, `zotero_tag`, `zotero_publish`, `fetch_missing`, `find_alternate_ids` | **No attachment upload at all.** `ZoteroClient` does `createSubCollection`/`createItems`/`updateItems`/`addTags` — no attachment item, no `/import`, no md5 registration. `PdfFetcher.getPdfFromUrl` returns `Optional<InputStream>` into the ehcache blob store and never writes a file to a real path. |
| 3 background crawl | `expand_citation_graph(direction, limit)` | No depth, no background. One hop over every session record, inline in the stdio read loop (`McpServer.java:111`), so a crawl blocks the server and workflow 4 cannot run while workflow 3 is running. `Session.expandedUp`/`expandedDown` do make re-running incremental — keep that. |
| 4 crawl status | `session_status` (record and link counts) | No frontier size, no error count, no per-API call count, no ETA, no job identity. |
| 5 metrics | `Analysis.pageRank` via jgrapht, `topic_model` LDA | Nothing significant. Keep. |
| 6 selection | `page_rank`, `topic_model` return scores | Nothing persists a selection. `remove_records` takes an id list, so the agent round-trips ids through context and destroys the rejected records. Selection must be a mark on the repository, not a delete. |
| 7 sync back | `zotero_publish` with subcollection, batching, `leftForANextCall` | Publishes from the whole session, not from the selection. Carries no PDFs (same gap as 2). |

Three structural facts drive every decision below:

- **State is a serialized Java object.** `Session implements Serializable`,
  persisted by `ObjectOutputStream` ([debt-001](/design/implementation/debt/debt-001-session-persistence-uses-java-serialization.md)).
  A crawl that outlives the process cannot be stored in it, and adding a job
  field to it breaks every existing `.ser` file.
- **The server is single-threaded.** One `while ((line = in.readLine()))` loop
  dispatches every tool inline.
- **Scores are calculated and discarded.** `BiblioFacade.rrfScore` sorts the
  merged collection, then `recordJson` emits no score, so the ranking cannot
  reach the agent that has to choose with it.

## Target architecture

Three layers with a hard boundary between the last two. The library layer must
stay usable as a plain Java API with no Zotero running and no MCP client
attached — that is what keeps it testable and what lets `prag` or a script use
it later.

```
  MCP tools (workflow-shaped)            <- thin; argument checking and JSON
  -----------------------------------
  RepositoryStore      CrawlEngine       <- the two new pieces of machinery
  (durable, versioned) (durable jobs)
  -----------------------------------
  BiblioFacade, MergePolicy, Analysis,
  ZoteroClient, PdfFetcher               <- existing library layer, unchanged role
```

### The repository store

The repository of record is the on-disk repository. Zotero is a **one-way
mirror**: the human's view, edited by the human, never the crawl's working
state. The sync reads the repository and writes Zotero; nothing reads Zotero
into the crawl except as an input seed set.

Chosen over "Zotero is the repository" because a depth-2 crawl from 25 seeds is
tens of thousands of records, and pushing every merge into the local Zotero API
is both slow and a way to wreck a library the developer cares about. Chosen
over "session stays authoritative, Zotero on demand" because that keeps two
stores that drift and leaves the agent deciding when to push.

The format is the open question, and it is plan-010's job, not this document's.
Requirements it must satisfy:

- **Versioned, readable, diffable.** A schema version in the file; a decode
  failure names the version and the field. This is debt-001 closing.
- **Append-friendly.** A crawl writes continuously. Rewriting a whole graph to
  survive a crash is not acceptable at 50,000 records.
- **Queryable without loading everything.** `rank` and `select` need "top 100
  by score" and "records with ≥ 30 cited-by links" without deserialising the
  lot.
- **No new heavy dependency unless it earns it.** Java 11, offline `~/.m2`
  (see [adr-002](/architecture/decision-records/adr-002-hand-rolled-mcp-protocol.md)).
  SQLite via a JDBC driver is the obvious candidate and the one to
  prototype first; a JSON-lines log plus an in-memory index is the fallback.

Records stay in the `record` model. The store is a persistence boundary, not a
new data model, and `Record`/`RecordWithCitations` are Immutables interfaces
that serialise awkwardly at best — expect an explicit DTO layer in the store,
which is also what makes the format versionable at all.

### The crawl engine

A depth-restricted traversal, run by a durable job, over the repository.

```
CrawlJob {
  id, seeds: Set<RecordReference>, direction: UP|DOWN|BOTH,
  depth: int, frontierPolicy, visited: Set, status,
  callsPerApi: Map<String,Integer>, errors, startedAt, finishedAt
}
```

- **Background.** A single `ExecutorService` owned by the server, one job at a
  time to start with. The stdio loop returns immediately with the job id.
- **Resumable and durable.** Job state lives in the store next to the
  repository, so killing the process and restarting resumes from the frontier
  rather than the seeds. This is what "durable job store" means here: the job
  survives the JVM, and so does its checkpoint.
- **Cancellable.** A cancel flag checked between records, not mid-socket. Same
  answer plan-009 reached for queue coordination.
- **Depth is the job's, not the caller's.** `expand_citation_graph` today can
  only do one hop over everything. A crawl applies a hop budget per record, so
  a paper reached at depth 1 is not re-expanded as if it were a seed.
- **Reported.** `crawl_status` returns visited / frontier / depth reached /
  errors / calls per API / wall time, straight from `CrawlJob`. That is
  workflow 4, and it works *because* the crawl is backgrounded.
- **Polite.** Per-API rate limits are already modelled (`TokenBuckets`), so the
  engine inherits them; what it must add is a hard per-job call ceiling so a
  runaway frontier cannot spend the day's OpenCitations budget.

plan-009's six open questions are resolved as: completeness threshold = has a
title and at least one resolvable identifier; concurrency = one worker per API,
one collector; coordination = shared `cancelled` set checked before each call;
Entrez batch = 200; per-call timeout = delegated to the client, not the queue;
queues = per-worker, drained by the collector. None of those are load-bearing —
they are parameters of the engine, and the engine is where they belong.

### PDF materialisation

One implementation, two consumers. `PdfFetcher` gains a sink that writes to a
real path (`repository/pdfs/<uuid>.pdf` or similar), returning a file
reference. Workflow 2 and 7 feed those to Zotero as attachments; `prag` reads
the same directory as its indexing input. Today it returns `Optional<InputStream>`
into a cache blob and writes nothing, so both consumers are blocked on the same
~30 lines plus a path convention.

The Zotero side needs three calls per file the local API does not let us skip:
create the attachment item, register the file, link it. Whether
`POST /<lib>/import` (translator-based, metadata + PDF in one call) is
available in the developer's Zotero build is unverified and decides whether
workflow 2 is three steps or one. Prototype it before writing the plan.

## Tool surface

From 29 to 12. The removed tools do not disappear as code — they stop being
things the model must order correctly, and become phases inside a workflow tool
or plain library calls.

| keep / new | tools |
|---|---|
| **search** | `search(query)` — now structured: terms, fields, date window, per-API. Returns records **with scores**. |
| **repository** | `repository(action, …)` — status, records, citations, add, remove, save, load, **select**, **selected**. One tool over the store; `select` marks, it does not delete. |
| **crawl** | `start_crawl(seeds, direction, depth, limit)` → job id. `crawl_status(job)`. `stop_crawl(job)`. |
| **analysis** | `rank(method, top, filters)` — `page_rank` \| `rrf` \| `cited_by`. `analyse(method)` — the LDA topic model. |
| **zotero** | `zotero_sync(collection, direction, includePdf)` — publish the selection, update metadata, attach PDFs. `zotero(action)` — status, collections, check, cleanup_report, apply_updates, tag. |

| deleted tool | becomes |
|---|---|
| `cites`, `cited_by`, `map_ids`, `fetch` | library calls, reached through `search`/`crawl`/`repository(add)`. Low value as tools once those exist. |
| `collect_search`, `collect_search_by_api`, `fetch_missing`, `find_alternate_ids` | phases inside `search` and `start_crawl`. |
| `expand_citation_graph` | `start_crawl` with `depth: 1`. |
| `add_records`, `remove_records`, `save_repository`, `load_repository`, `session_records`, `session_citations` | `repository(action=…)`. |
| `page_rank`, `topic_model` | `rank`, `analyse`. |
| `zotero_import`, `zotero_publish`, `zotero_check`, `zotero_cleanup_report`, `zotero_apply_updates`, `zotero_tag`, `zotero_status`, `zotero_collections` | `zotero_sync` + `zotero(action=…)`. |
| `references_from_pdf`, `citation_styles` | keep as library calls; revisit whether either needs to be a tool at all. |

`references_from_pdf` extracts references from a PDF but never parses its
content, so it is a different thing from what `prag` will do; do not merge them
on the assumption they are the same feature.

## Relationship to existing documents

- **feat-005 (Zotero curated repository bridge, `final`)** stays `final` and
  keeps its reading side, which is used by the clean-up report. Its *publish*
  half is superseded by `zotero_sync` here. When this feature is scheduled,
  feat-005's status and description need updating to say the mirror is
  one-way; that edit belongs to the plan that makes it true, not to this
  document.
- **plan-009 (async fetch queue, `draft`, unassigned)** is absorbed. It solves
  fetch latency for one call; this feature needs a durable job anyway, and
  building both is building the same executor twice. plan-011 supersedes it.
- **feat-002, feat-003** are the citation-graph and analysis features this
  extends rather than replaces.
- **OpenAlex** is deliberately **not** folded in here — it is a separate
  feature, not yet written up as a roadmap idea. It replaces the Unpaywall OA role, adds
  search and fetch, and — because `referenced_works` ships inside the record —
  makes a depth-1 reference crawl one call per seed instead of N. That changes
  the crawl's cost model, so it is worth deciding *before* plan-011 hard-wires
  per-source traversal, even though the provider itself is a separate feature.
  Recorded here as a constraint on the crawl design, not as scope.
- **prag** lands on top of the PDF directory and the `Record` model, and
  replaces nothing here except the LDA in `topic_model` if it ever does topics
  better. No interface is designed for it now beyond keeping the PDF sink a
  plain directory.

## Non-goals

- Multi-job concurrency. One crawl at a time; the queue can come later.
- Bidirectional Zotero sync. Mirror is one-way out.
- A web UI. ~~A persistent server. Still stdio, still one process.~~ **Revised
  2026-11-03:** a persistent server is now the point. A durable repository and a
  crawl that outlives the request that started it make the process a service, so
  it listens on a loopback port and speaks MCP over HTTP through the official
  SDK — see [adr-006](../../architecture/decision-records/adr-006-adopt-mcp-sdk-over-http.md) and
  [plan-014](../implementation/plans/plan-014-mcp-sdk-http-server.md). stdio is deleted. Still one
  process; no longer one client, and no longer one conversation.
- Changing the `record` model or `MergePolicy`.
- Anything that requires Zotero to be running in order to crawl, rank or
  select.

## Success criteria

- A crawl of 25 seeds to depth 2 completes with the server responsive:
  `crawl_status` answers during the crawl, and `repository` reads the records
  already collected.
- Killing the server mid-crawl and restarting resumes from the frontier.
- `rank` and `search` return scores in the JSON, so selection never depends on
  the model remembering which records were at the top of an earlier response.
- `zotero_sync` takes the selection, updates metadata, and produces readable
  PDF attachments in the local library.
- Zotero closed: search, crawl, rank, select all work; `zotero_sync` fails with
  a clear message.
- `Session`'s Java serialization is gone; debt-001 closes.
- Offline test tier green; the new engine has offline tests with a faked
  facade, and a live crawl test against a small seed set.

## Risks

- **Store format churn.** A versioned format chosen wrong is expensive to
  change after real crawls exist. Prototype before committing to SQLite.
- **Graph size.** Depth-2 from a productive seed can be 10⁴–10⁵ nodes. Ranking
  on that must not load everything into memory; if it does, the store design
  failed and the symptom shows up late, in `rank`.
- **Single-threaded server.** Adding the executor is small, but tool handlers
  mutate `Session` today without synchronisation. The handler that returns a
  job must not share mutable state with the crawl thread.
- **Zotero API limits.** Write paths are already batched with
  `leftForANextCall`; PDFs make that limit much hotter.

## Status — 0.2.0: six of seven workflows

Built: the durable repository ([plan-010](../implementation/plans/plan-010-versioned-repository-store.md)),
the background crawl ([plan-011](../implementation/plans/plan-011-background-crawl-engine.md)), the
six-tool surface ([plan-012](../implementation/plans/plan-012-workflow-tool-surface.md)) and the MCP
server over HTTP ([plan-014](../implementation/plans/plan-014-mcp-sdk-http-server.md)).

Against the success criteria at the top of this document:

| criterion | state |
|---|---|
| a crawl runs with the server responsive | **yes** — the crawl is on its own thread and `crawl status` answers during it |
| killing the server mid-crawl resumes from the frontier | **yes**, asserted offline |
| `rank` and `search` return scores in the JSON | **yes** — `score` and `matchedApis` |
| `zotero_sync` takes the selection and updates metadata | **partly** — it publishes and reports; **no PDFs** |
| Zotero closed: everything else works | **yes**, asserted over the wire |
| `Session`'s Java serialization gone, debt-001 closed | **yes** |
| offline tier green, engine tested with a faked facade, live crawl test | **yes** |

**Workflow 2's PDF half is the gap, and it is now half closed.** `PdfSink` and
`PdfFetcher.fetchTo` write a real file — verified against a live 6.3 MB
open-access PDF — so the directory `prag` needs exists in all but name. What is
missing is the Zotero attachment, whose shape
[prototype-003](../prototypes/prototype-003-zotero-attachment-paths.md) settled
on 2026-11-02: the local Web API rather than the connector protocol, so that
`checkWriteTarget` keeps confining every write. Its five remaining probes all
write, and each needs a human to approve a dialog in the Zotero window.

The non-goal about a persistent server was revised rather than met: the server
is now a service on a loopback port, which is what made workflows 3 and 4
possible at all.
