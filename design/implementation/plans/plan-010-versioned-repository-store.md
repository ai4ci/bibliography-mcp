---
title: Versioned repository store
status: final
targetVersion: 0.2.0
tags:
- persistence
- repository
- storage
HAS_FEATURE:
- feature:feat-010-crawl-engine-with-zotero-mirror.md
DEPENDS_ON:
- plan:plan-008-search-deduplication.md
---
# Plan 010 — Versioned repository store

Replace `Session`'s Java serialization with a durable, queryable, versioned
repository. This is [debt-001](/design/implementation/debt/debt-001-session-persistence-uses-java-serialization.md)
closing, and the precondition for plan-011: a crawl that outlives the process
needs somewhere to put its frontier.

## Problem

`Session implements Serializable` and saves with `ObjectOutputStream`
(`mcp/Session.java:327`). Consequences:

- Any field change, record-class change, or jgrapht/exotic-datatypes upgrade
  breaks existing `.ser` files with `InvalidClassException`.
- The file is unreadable and untestable by inspection.
- The whole graph must be in memory, so a crawl cannot grow past what fits, and
  `rank`/`select` cannot query without a full load.
- There is no place to put crawl job state that survives a restart.

## Goals

1. Load and save a repository of 100,000 records plus their citation edges
   without holding all of it in memory for a top-N query.
2. Append during a crawl; a crash mid-write loses at most the last write, not
   the repository.
3. A schema version in the file, and a decode failure that names the version
   and the offending field.
4. Two processes can read the same repository concurrently.
5. `Save`/`load` from the tool surface keep working with the same arguments, so
   the format change is invisible to an agent mid-workflow.

## Non-goals

- Syncing, merging or reconciling two repositories.
- Compression and archival tiers.
- Anything Zotero. The store does not know Zotero exists.

## Design

A `RepositoryStore` interface in `record` or a new `repository` package, with
the persistence mechanics out of `Session`, which becomes a thin in-memory view
over it:

```
RepositoryStore
  RepositoryMeta meta()                                  // schemaVersion, createdAt, jobId
  void putRecord(Record r)                               // merge-on-conflict via MergePolicy
  Optional<Record> getRecord(RecordReference ref)
  Stream<Record> records()                               // lazy
  void putCitation(CitationLink link)
  Stream<CitationLink> citations()
  void markSelected(RecordReference ref, boolean selected)
  Stream<Record> selected()
  void putJob(CrawlJob job);  Optional<CrawlJob> job(String id)
```

`Record` and `RecordWithCitations` are Immutables interfaces, which serialise
badly and cannot evolve. Expect an explicit DTO layer: store fields in and out
by hand, version-tagged. That is the boring part and it is what makes the
format stable, so do not try to skip it with reflection.

`markSelected` is workflow 6. Selection is a column, not a delete: rejected
records stay in the graph so PageRank is computed over the real network and the
decision can be reversed.

## Options for the backing format

| # | Option | Wins | Costs |
|---|---|---|---|
| A | **SQLite via `org.xerial:sqlite-jdbc`** | one file, real queries (`ORDER BY score LIMIT 100`), WAL lets a reader and the crawl coexist, SQL makes ad-hoc analysis trivial, `CrawlJob` is a row | new dependency (~13 MB jar with native libs); needs a JDBC-in-Java-11 sanity check |
| B | **JSON-lines log + in-memory index** | no dependency, diffable and greppable, append is trivial, trivially testable | every query is a scan; index must be rebuilt on load; compaction is a feature we'd have to write |
| C | **H2 / HSQLDB** | pure Java, no native code | heavier than SQLite for a single-file embedded use, two engines to reason about |

Recommendation: **A, prototyped against B as the control.** Prototype before
deciding, because the decision is expensive to reverse once real crawls exist:
build the same 100k-record write + "top 100 by score" + "edges for node X"
exercise both ways and measure. If the SQLite driver is a problem in the offline
`~/.m2` build (the constraint that produced
[adr-002](/architecture/decision-records/adr-002-hand-rolled-mcp-protocol.md)),
B is a fine answer for repositories an order of magnitude smaller, and the
interface above hides which is in place.

## Work items

1. Prototype A and B against the goals; record the measurements here and pick.
   Repository lives under
   [adr-005](/architecture/decision-records/adr-005-repository-uses-xdg-data-home.md),
   not the `/tmp` cache.
2. `RepositoryStore` interface + DTO layer + schema version constant.
3. Migration reader: decode an old `.ser` file once, write it into the new
   format, report what could not be read. `load_repository` calls it when the
   file is a Java serialization stream (`AC ED 00 05` magic).
4. Rework `Session` (`main:java/uk/co/terminological/bibliography/mcp/Session.java`)
to delegate: `add`, `merge`, `status`, `records`,
   `citations`, `expand*` keep their signatures so `McpTools` does not change in
   this plan.
5. Selection column + `selected()`.
6. `CrawlJob` persistence (the table only; the engine is plan-011).
7. Tests: round-trip, version mismatch names the version, concurrent read while
   writing, `.ser` migration, 10k-record top-N without full load.

## Open questions

- ~~Where does the default repository live?~~ Decided 2026-11-02: an implicit
  `$XDG_DATA_HOME/bibliographic-api-client/repositories/` with a
  `repository-directory` override, one repository per subdirectory. See
  [adr-005](/architecture/decision-records/adr-005-repository-uses-xdg-data-home.md).
  It must **not** reuse `cache-directory`, which points under `/tmp` and is
  wipeable by design.
- Confirm the repository-name scheme (a `name` argument rather than a path) is
  workable for `repository(action=save|load)` once the smoke script exercises it.
- Does one store hold multiple named repositories, or one repository per file?
  ADR-005 assumes the former; revisit if the tool arguments get awkward.
- Retention: does a finished crawl's frontier stay in the file forever?
- Is `MergePolicy` applied on `putRecord` (write-time merge, cheap to read) or
  on read (keeps provenance per source, expensive)? Write-time is what
  `MergedRecord` does today.

## Decision — SQLite (option A), measured 2026-11-03

Both options were built against the goals above and run at two scales. Writes
are checkpointed every 1,000 records, as a crawl would; the query figures are
from a *fresh* reader, which is the case that matters — a client asking for the
top of the repository while the crawl that filled it is still running.

**100,000 records, 300,000 edges**

| | A: SQLite (WAL) | B: JSON lines + in-memory index |
|---|---|---|
| write | 516 ms | 101 ms |
| index build / load | 151 ms | 195 ms (every process start) |
| top 100 by score | 16 ms | 48 ms |
| edges into one node | 12 ms | 0 ms |
| **heap held to answer** | **~0** | **46 MB** |
| read while another writes | yes | no |
| file | 38.8 MB | 33.4 MB |

**500,000 records, 2,000,000 edges**

| | A: SQLite (WAL) | B: JSON lines + in-memory index |
|---|---|---|
| write | 3,110 ms | 290 ms |
| index build / load | 1,105 ms | 959 ms (every process start) |
| top 100 by score | 41 ms | 184 ms |
| edges into one node | 73 ms | 0 ms |
| **heap held to answer** | **~0** | **274 MB** |
| read while another writes | yes | no |
| file | 247 MB | 202 MB |

### What the numbers say

- **B is not slow; B is resident.** Its index costs ~550 bytes per record, and
  that is a *minimal* index — score and inbound edges only. A real one carrying
  titles, identifiers and the selection flag is several times that, in a process
  that also runs a crawl and serves HTTP clients. 46 MB → 274 MB between the two
  scales is the whole argument: the cost is linear in the repository and it is
  paid whether or not anyone asks a question.
- **A's write cost is irrelevant.** SQLite is 10× slower at writing and it does
  not matter: 500,000 records cost 3 seconds of store time against hours of API
  calls to produce them. The store is never the bottleneck in a crawl.
- **B fails goal 1 by construction and goal 4 outright.** "Top 100 by score
  without deserialising the lot" is exactly what an in-memory index cannot do,
  and nothing coordinates two readers of an appending file.
- **B's one real win** — 0 ms edge lookup from a prebuilt map — is available in A
  too, by keeping a hot subgraph in memory when the analysis needs it. That is a
  cache over a store, not a reason to choose the store.

### Decided

**SQLite via `org.xerial:sqlite-jdbc`**, WAL journal, `synchronous=NORMAL`, a
`meta` table carrying `schemaVersion`. Option B is recorded here as the control
it was, and is the fallback only if the driver becomes a problem on a platform
this has to run on.

Open questions this settles:

- ~~Is `MergePolicy` applied on `putRecord` or on read?~~ **On write.** It is
  what `MergedRecord` already does, and a read-time merge would mean loading
  every contribution for a top-N query, which is the thing SQLite was chosen to
  avoid.
- ~~Does one store hold multiple named repositories, or one per file?~~ **One
  repository per file**, under adr-005's directory. With the server now a
  service started against one repository
  ([adr-006](../../../architecture/decision-records/adr-006-adopt-mcp-sdk-over-http.md)), switching repositories
  mid-session stops being a thing the tool surface has to express — which is why
  `save` and `load` leave the surface in plan-012.

## Built — `repository` package

`RepositoryStore` with `SqliteRepositoryStore` behind it, `RepositoryMeta`,
`RepositoryException`. Schema version 1, WAL, `synchronous=NORMAL`,
`busy_timeout=5000`.

Tables: `meta`, `record` (the merged view every query reads), `contribution`
(what each source said), `author`, `identifier`, `citation`. Indexes on score,
selection and citation target.

The shape worth noting is the pair: **`putRecord` writes a contribution and
re-materialises the merged view from all of them.** Queries read the materialised
row and never merge; a third source merges with the first two rather than into
their merged result; and the clean-up report keeps the per-source claims it
needs. `getRecord` rebuilds the merged record from the contributions, which is a
handful of rows, and `topByScore` takes its keys from the score index and
hydrates only those — so "the top hundred" costs a hundred hydrations rather than
a repository.

Pinned by `TestRepositoryStore` (11 tests): survives the process, refuses a
later schema version **naming both versions**, two sources become one record,
contributions are kept, citations round-trip in the issue-007 direction, the same
edge twice is one edge, selection is reversible and deletes nothing, 5,000
records yield a top-10 from the index, and a second reader sees the repository
while the first is still writing.

## Two work items deliberately not done

- **Item 4, `Session` delegating to the store.** The plan wrote it so that
  `McpTools` need not change — but
  [plan-012](plan-012-workflow-tool-surface.md) deletes `McpTools` and
  [plan-014](plan-014-mcp-sdk-http-server.md) deletes the server around it.
  Reworking `Session` into a delegating shell, to then delete it, is work that
  exists only to be thrown away. `Session` stays exactly as it is until the new
  server lands and takes it with it. **debt-001 therefore closes when `Session`
  goes, not here.**
- **Item 3, the `.ser` migration reader.** Worth building only if a `.ser`
  repository exists that is worth keeping, and those files are already fragile to
  every class change that has landed since they were written — which is what
  debt-001 says. Left for the developer to say whether any exist; if none do,
  this item should be struck rather than built.

## A work is found by any identifier it is known by

Found by driving the built server end to end: `rank(method=cited_by)` returned
its top three works as anonymous placeholders — `citedBy=2 (no record fetched)` —
although the crawl had fetched and stored them.

The cause is that a record is stored under the identifier it was **fetched** by,
while a citation edge names the same work by whichever identifier the **citing
source** used: a DOI from Crossref, an OpenAlex id from `referenced_works`. The
store recorded the equivalence in `identifier` and then did not use it, so
`getRecord` missed by one name and the ranking lost exactly the works it was
meant to surface.

`getRecord`, `contributions`, `setScore` and `markSelected` now resolve through
that table before giving up. Pinned by `aWorkIsFoundByAnyIdentifierItIsKnownBy`
and `theMostCitedWorkIsNamedRatherThanAnonymous`, both proven to fail first.

The same crawl after the fix:

```
rank cited_by  ->  citedBy=2 Observational Studies: Matching or Regression?
                   citedBy=2 Factors associated with COVID-19-related death…
                   citedBy=2 Deep Mutational Scanning of SARS-CoV-2 Receptor…
```

Worth noting what this is not: it is not identifier *mapping*. The store joins
only equivalences a record already carried, and finding that two records are the
same work when neither names the other is still `IdMapper`'s job, at search time.
