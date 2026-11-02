---
title: Versioned repository store
status: draft
targetVersion: unassigned
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

- Where does the default repository live? Options: the `cache-directory` from
  `interface-002`, a new `repository-directory`, or an explicit path per
  `save`. Explicit beats implicit for a file the developer will want to keep.
- Does one store hold multiple named repositories, or one repository per file?
  Multiple named repositories matches "create a repository from these search
  results" better but complicates the tool arguments.
- Retention: does a finished crawl's frontier stay in the file forever?
- Is `MergePolicy` applied on `putRecord` (write-time merge, cheap to read) or
  on read (keeps provenance per source, expensive)? Write-time is what
  `MergedRecord` does today.
