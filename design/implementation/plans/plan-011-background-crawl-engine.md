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
  Map<String,Integer> callsPerApi;  Map<String,Integer> creditsPerApi;
  int errors; Instant startedAt, finishedAt;
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
  inherited for free. The job adds a **spend ceiling**: free APIs count calls,
  and OpenAlex counts **credits read back from `X-RateLimit-Credits-Used`**,
  because a downward crawl there is free per node while an upward one costs 1 per
  seed and a search costs 10 — see
  [prototype-002](/design/prototypes/prototype-002-openalex-cost-and-record-shape.md).
  A call-count ceiling alone would be wrong in both directions on that source.
  When the ceiling is hit the job ends `DONE` with `stoppedBy: spendCeiling`
  recorded, so status explains why it stopped rather than looking truncated.
- **Failure.** A client that throws marks the record `ERROR`, increments
  `errors`, and continues. Never let one bad id kill a crawl. Note that
  `CachingApiClient` maps non-2xx to `Optional.empty()` (`CachingApiClient.java:403`),
  so "no result" and "API broken" are indistinguishable at this layer — that is
  [issue-013](/design/implementation/issues/issue-013-one-failing-api-empties-multisearch.md)
  and the crawl inherits it; the error count only catches exceptions that do
  escape. It also means a **rate-limit refusal looks like an empty result** on a
  credit-metered API, which is the strongest argument for fixing issue-013 before
  a large crawl depends on it.

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
  **Reconsidered:** with OpenAlex the default `depth` and `maxRecords` can be far
  larger than anything sensible today, since a downward hop is free there — so if
  [feat-011](/design/features/feat-011-openalex-as-a-provider.md) lands first,
  revisit the defaults here rather than inheriting OpenCitations-era ones.
- Single job at a time is decided; does that need to be a lock on the store, in
  case two server processes open the same repository?

## Built — `crawl` package

`Direction`, `JobStatus`, `CrawlJob`, `CrawlSource`, `CrawlEngine`. Job
persistence is in the store: a `job` table for the scalars and `job_node` for
the frontier, as rows rather than a blob so that a frontier is streamed back
rather than parsed whole.

`CrawlSource` is the engine's whole view of the outside world — edges each way
and records at the ends of them — and `BiblioFacade` implements it unchanged.
The engine never learns which APIs answered, so the budget gates and the
per-client direction conventions stay where they already are, and the traversal
can be exercised over a citation graph declared in a test.

Pinned by `TestCrawlEngine` (10 offline tests, written from the list above):
depth 1 stops at the neighbours, depth 2 reaches their neighbours, a node
reached shallower is expanded exactly once, `BOTH` is one hop, the ceiling stops
the job with `stoppedBy=recordCeiling`, one throwing identifier is counted and
the crawl carries on, cancelling halts within a record, a second crawl is
refused naming the running one, edges and records reach the repository, and a
job killed mid-frontier **resumes from the same frontier and visited set** in a
new process.

### Live — 2 seeds, depth 1, DOWN

```
CrawlJob[… DONE visited=98 frontier=0] stoppedBy=frontierDrained calls={cites=2}
repository holds 51 records and 129 citations
```

Two things that measurement says. **Two calls expanded two seeds** — the
reference lists come back whole, which is why `DOWN` is the cheap direction and
why OpenAlex's `referenced_works` mattered to the cost model. And **98 nodes
yielded 51 records**: half the references in a reference list do not resolve to
a record any API will return. The graph is therefore always larger than the
repository, which is the honest state of affairs rather than a gap to close.

## Open questions, resolved

- **Does the crawl fetch a record for every node it visits, or only for the ones
  that survive?** It fetches **every** node, in batches of 100. The plan
  recommended the opposite — keep the reference, backfill later — on the
  arithmetic of 10⁴–10⁵ calls. [feat-011](../../features/feat-011-openalex-as-a-provider.md)
  changed that arithmetic: a batched fetch is 100 identifiers for one credit, so
  the fetch is no longer the expensive part of a hop. A repository of references
  with no records cannot be ranked, selected from or synced, which would push
  the cost onto every later step instead of removing it.
- **Is one job at a time a lock on the store?** Not yet. The engine refuses a
  second job in the same process; two server processes on one repository is
  still open, and is the same question plan-010 and
  [adr-006](../../../architecture/decision-records/adr-006-adopt-mcp-sdk-over-http.md) leave standing.

## Not done here

Work items 4, 5 and 6 — the server owning the executor, the `crawl` tool
handlers, and deleting `expand_citation_graph` with the rest of `McpTools` —
belong to the commit that stands up the SDK server
([plan-014](plan-014-mcp-sdk-http-server.md)), for the same reason plan-010
did not rework `Session`: they would be written against a server that is about
to be deleted.
