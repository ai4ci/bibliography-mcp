---
title: Background crawl engine
status: draft
targetVersion: unassigned
tags:
- citations
- crawl
- concurrency
HAS_FEATURE:
- feature:feat-010-crawl-engine-with-zotero-mirror.md
DEPENDS_ON:
- plan:plan-010-versioned-repository-store.md
---

# Plan 011 — Background crawl engine

Depth-restricted citation traversal that runs in the background, survives a
restart, and reports its own progress. Delivers workflows 3 and 4 of
[feat-010](/design/features/feat-010-crawl-engine-with-zotero-mirror.md), and
supersedes [plan-009](/design/implementation/plans/plan-009-async-fetch-queue.md)
— that plan solves fetch latency for a single call, and building it alongside a
durable job store means building the same executor twice.

## Problem

`expand_citation_graph(direction, limit)`
(`mcp/McpTools.java`) does one hop over every session record, inline in the
stdio read loop (`mcp/McpServer.java:111`). Therefore:

- There is no depth. `Session.expandUpCitationGraph(limit)` expands
  `retrievedIds()` minus `expandedUp` — a hop budget applied to the whole
  session, not per record.
- There is no background. A crawl blocks the server, so nothing else —
  including `session_status`, which is how you would watch it — can run.
- There is no resume. Kill the process and the frontier is gone.
- `limit` is a tripwire, not a budget: over the limit it logs
  "Exceeded limit size for expansion" and does nothing at all.

## Goals

1. `start_crawl(seeds, direction, depth, limit)` returns a job id immediately.
2. `crawl_status(job)` answers **during** the crawl: visited, frontier, depth
   reached, errors, calls per API, elapsed.
3. Restarting the server resumes a running job from its frontier.
4. `stop_crawl(job)` cancels between records.
5. A per-job API-call ceiling, so a runaway frontier cannot spend the day's
   OpenCitations budget.
6. `expand_citation_graph` becomes `start_crawl(depth: 1)`, and `depth: 1`
   jobs behave exactly like the old tool.

## Non-goals

- Multiple concurrent jobs. One at a time; a second `start_crawl` while one runs
  is refused with the running job's id.
- Distributed or multi-process crawling.
- Relevance-directed crawling (only expand the promising frontier nodes). The
  selection machinery in plan-012 makes that possible later; do not design it
  now.
- Traversal that skips the per-source `CitesMapper`/`CitedByMapper` clients. If
  OpenAlex later ships `referenced_works` inside the record, that is a
  *cheaper mapper implementation*, not a different engine.

## Design

```
CrawlJob {
  String id; Set<RecordReference> seeds; Direction direction; int depth;
  int maxRecords; Map<RecordReference,Integer> depthOf;
  Deque<RecordReference> frontier; Set<RecordReference> visited;
  JobStatus status;                 // RUNNING | PAUSED | DONE | CANCELLED | FAILED
  Map<String,Integer> callsPerApi;  int errors; Instant startedAt, finishedAt;
}
```

- **Traversal.** BFS from the seeds, `depthOf` recorded per node. A node already
  reached at a shallower depth is never re-expanded. `direction` per hop: `UP`
  follows `cited_by`, `DOWN` follows `references`, `BOTH` does both and counts
  as one hop. BFS is the right order here because the budget is depth, and
  because the shallow nodes are the ones worth ranking first if the crawl is
  stopped early.
- **Execution.** One `ExecutorService` owned by the server, single worker thread
  to start with. The tool handler returns the id; the loop never blocks.
- **State.** Every field of `CrawlJob` is persisted through plan-010's
  `putJob`/`job`, checkpointed every N records (N = 50, tune later). On startup
  the server looks for a `RUNNING` job and offers to resume it rather than
  resuming silently — a crawl the developer did not ask to restart is a surprise
  spend of API budget.
- **Coordination.** A `cancelled` flag checked before each record, never
  mid-socket. Same decision plan-009 reached for queue cancellation: do not
  touch in-flight HTTP.
- **Politeness.** Per-API rate limits already exist (`TokenBuckets`) and are
  inherited for free. The job adds `maxCalls`; when the ceiling is hit the job
  ends `DONE` with `stoppedBy: callCeiling` recorded, so status explains why it
  stopped rather than looking truncated.
- **Failure.** A client that throws marks the record `ERROR`, increments
  `errors`, and continues. Never let one bad id kill a crawl. Note that
  `CachingApiClient` maps non-2xx to `Optional.empty()`, so "no result" and
  "API broken" are indistinguishable at this layer — that is
  [issue-013](/design/implementation/issues/issue-013-one-failing-api-empties-multisearch.md)
  and the crawl inherits it; the error count only catches exceptions that do
  escape.

### Resolved from plan-009's open questions

| question | answer |
|---|---|
| completeness threshold | title plus at least one resolvable identifier |
| concurrency | one worker thread per job; per-API calls stay sequential |
| queue coordination | shared `cancelled` flag, checked before each call |
| Entrez batch size | 200 per `efetch` |
| per-call timeout | the client's business, not the queue's — see [debt-009](/design/implementation/debt/debt-009-shared-read-timeout-is-a-trap.md) |
| queue structure | plain `ArrayDeque` in the job; single consumer, so no blocking queue |

## Work items

1. `CrawlJob` + `JobStatus` + `Direction` in a new `crawl` package.
2. `CrawlEngine` doing the BFS over `RepositoryStore` + `BiblioFacade`.
3. Checkpoint/restore through plan-010's job persistence.
4. Server: own the executor; a `RUNNING` job at startup is reported, not
   auto-resumed.
5. Handlers `start_crawl` / `crawl_status` / `stop_crawl`.
6. Delete `expand_citation_graph`, `Session.expandUpCitationGraph`,
   `Session.expandDownCitationGraph`, and the `expandedUp`/`expandedDown` sets.
7. Tests.

## Tests

Offline, with a faked facade — no network:

- depth 1 from 2 seeds visits the seeds and their neighbours, no further.
- depth 2 reaches neighbours-of-neighbours and does not re-expand a node
  already seen at depth 1.
- `BOTH` counts as one hop.
- `maxCalls` ceiling stops the job and records `stoppedBy`.
- a facade that throws on one id increments `errors` and keeps going.
- serialise a job mid-crawl, restore it, continue: the visited set and frontier
  are identical to the pre-restart values.
- `stop_crawl` halts within one record.
- `start_crawl` while running is refused and names the running job.

Live, `@Category(LiveTest.class)`, one small job:

- 3 seeds, depth 1, `DOWN` against the real APIs, asserting the frontier drains
  and `callsPerApi` is non-zero. Not run in CI.

## Open questions

- Does the crawl fetch full records for every node it visits, or only the ones
  that survive to the repository? Fetching everything at depth 2 is the
  difference between 10⁴ and 10⁵ API calls. Recommendation: keep the
  *reference* for every visited node, fetch the *record* only for nodes the
  caller later asks about, with `fetch_missing`-style backfill as an explicit
  step.
- Should `start_crawl` accept a saved query or a selection instead of an
  explicit id list, so workflow 3 reads "crawl what I selected"? That is a
  plan-012 argument-shaping question; the engine takes ids either way.
- Single job at a time is decided; does that need to be a lock on the store, in
  case two server processes open the same repository?
