---
title: Async fetch queue
status: draft
targetVersion: unassigned
HAS_FEATURE:
- feature:feat-001-expose-bibliographic-apis-over-mcp.md
DEPENDS_ON:
- plan:plan-008-search-deduplication.md
---

# Plan 009 — Async queue-based fetch

## Problem

`BiblioFacade.fetch()` iterates all configured APIs sequentially. Each call
blocks until it returns. If any API is rate-limited or slow the whole
operation stalls, even when another API already has the answer. The caller
waits for every API to complete before seeing anything.

## Goals

1. No single slow or rate-limited API stalls the whole fetch.
2. A record resolved by one canonical API is not re-fetched from others.
3. Entrez's batch-fetch capability is used naturally (many PMIDs in one call).
4. Results arrive incrementally rather than all-or-nothing.
5. Each API's cache is populated independently; a unified facade cache avoids
   repeated cross-API work.

## Design sketch

```
Input: Collection<RecordReference> ids

1. Create per-service queues seeded with all ids:
     crossrefQ ← ids  (iterates one DOI at a time)
     entrezQ    ← ids  (batches PMIDs in one call)
     epmcQ      ← ids  (iterates one id at a time)

2. Shared state (accessed by all service workers):
     resolved: Map<RecordReference, MergedRecord>
     pending:  Set<RecordReference> (initially: all ids)

3. Fire each service's worker asynchronously:
     Each worker pulls from its queue, calls the API, pushes results
     into a shared incoming channel.

4. A collector actor (or single-thread coordinator) processes the channel:
     for each result (record, fromApi):
       if record is "complete enough":
         add to resolved, remove from pending and from all queues
       else:
         push the id into another service's queue
     
     When pending is empty or all queues are exhausted:
       return resolved
```

### Completeness threshold

A record is "complete enough" when it has enough metadata for the caller's
purpose. The simplest default: a title (or abstract or identifier beyond the
one we searched with) — something that proves the API actually found the work
rather than just echoing back our request.

This may become configurable later (quick vs full).

### Special case: Entrez batching

Entrez `efetch` accepts multiple PMIDs in a single HTTP request. The Entrez
worker can batch N pending PMIDs from its queue into one call rather than
iterating one-at-a-time. This is an optimisation inside the same queue
abstraction — the worker flushes its queue in batches when enough PMIDs
accumulate.

### Caching

- Each client (`CachingApiClient`) already caches its own HTTP responses.
- A second facade-level cache (`Map<RecordReference, MergedRecord>`) avoids
  re-fetching from other APIs once a record is resolved.
- The facade cache is populated as records are resolved and checked before
  pushing to per-service queues.

### Supplementary APIs (Unpaywall, medRxiv)

These are not canonical metadata sources. They are consulted only when a
specific tool asks for OA location (Unpaywall) or preprint reference lists
(medRxiv). They are not part of the fetch loop by default.

### `BiblioFacade.fetch()` — rewrite

Replace the sequential `fetchApis.forEach(...)` block (lines ~243-267) with
the queue-based async loop described above.

New methods in `BiblioFacade`:

```java
/**
 * Check whether a record has enough metadata to be considered resolved.
 * Currently: has a title, or has at least two different identifiers (a
 * DOI + a PMID, say, which proves the API cross-referenced it).
 */
private boolean isComplete(Record r) { ... }

/**
 * Worker body for one fetch API. Pulls from its queue, fires the API,
 * returns a Map of resolved ids → records.
 *
 * Differs per API: CrossRef and Europe PMC iterate one-at-a-time;
 * Entrez batches.
 */
private Map<ImmutableRecordReference, ? extends Record> fetchFromApi(
    String apiName,
    Queue<RecordReference> queue
) { ... }
```

### What does NOT change

- `RecordFetcher` interface (callers keep the same API).
- The per-client `CachingApiClient` and its Ehcache.
- `Session.fetchMissingRecords()` / `Session.fetchRecords()` — they delegate
  to `facade.fetch()`.
- MCP tool signatures.

## Open questions

1. **Completeness threshold** — what fields make a record "done"? Title alone?
   Title + authors? Any identifier beyond the search key? Needs a concrete
   default to implement.

2. **Concurrency model** — Java 11 `CompletableFuture` with a custom thread
   pool, or a simpler `ExecutorService` + `Future` pattern? Each worker runs
   in its own thread (or one thread per API). The collector is single-threaded.

3. **Queue coordination** — how does a resolved id get removed from another
   service's queue mid-flight? Simplest approach: workers check a shared
   `Set<RecordReference> cancelled` before each API call. No need to modify
   in-flight HTTP requests; just skip dequeued ids that were already resolved.

4. **Entrez batch size** — `efetch` has no documented limit on PMID count per
   call. What batch size to use? 20? 50? The queue abstraction makes this a
   parameter.

5. **Timeout per API call** — how long to wait for each individual API call
   before treating it as "no result"? 15 s? 30 s?

6. **Thread safety of per-service queues** — using `ConcurrentLinkedQueue` or
   a blocking queue? The simplest: each worker has its own
   `ArrayDeque`/`ArrayList` snapshot, and the collector removes resolved ids
   from the central `pending` set.

7. **How does this interact with the existing `fetchBySource()` method?**
   Currently it returns per-API unmerged lists. The async queue model still
   can produce this — it just needs to track which API contributed which
   fields.


- Threading complexity: the current code is purely synchronous. Introducing
  concurrent workers adds a new class of bugs (race conditions on the shared
  pending set, ordering of results).
- Cancellation semantics: Java has no clean way to cancel an in-flight HTTP
  call. If CrossRef is mid-request when we resolve all IDs, we can skip that
  response but cannot abort the connection.
- If the thread pool is unbounded, many concurrent requests could trigger
  rate-limiting that a sequential loop would avoid. The queue model should
  rate-limit per API, not amplify load.
