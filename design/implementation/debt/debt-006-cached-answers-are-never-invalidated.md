---
title: Cached answers are never invalidated
status: open
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- cache
- data-integrity
IMPACTS:
- main:java/uk/co/terminological/bibliography/CachingApiClient.java
---

A cached answer is never invalidated, and an empty one is cached like any other,
so an upstream gap at fetch time becomes a permanent "no data" for that request.

Reproduction (2026-09, one API, one request, two caches):

```
apis.getEuropePMC().fetch({DOI:10.1136/bmjqs-2018-008370})
  with the existing cache dir  -> 0 records
  with a fresh cache dir       -> 1 record    ("Calling url: …/search?query=DOI:10.1136/bmjqs-2018-008370…")
```

Failures are *not* cached — `CachingApiClient.call` returns before `cache.put`
on a non-success status or a parse exception — so what is being served is a
**successful response that happened to contain no results**. The caches are named
`forever` (no expiration) and `week` (7-day TTL), and CrossRef, Unpaywall,
PMCIDClient and PdfFetcher each opt in explicitly via `cacheForever()`, so an
empty result can be pinned indefinitely.

The MCP tools expose no cache bypass either, so an agent that gets a wrong answer
cannot ask again — it depends on a human clearing a directory. Consequence: the
server can report "nothing found" for something that exists, with no indication
that the answer came from a cache rather than the API. That is worse than a
failure, which at least surfaces.

Severity is reduced by
[issue-004](/design/implementation/issues/issue-004-truncated-responses-are-cached-as-empty.md):
a body that fails validation is now rejected on read, the entry dropped and the
call refetched, so caches holding unparseable content self-heal. What remains is
the absence of any bypass for answers that are *well formed but wrong* — an
upstream mistake cached as truth.

It also produced a false lead: a measurement against a stale cache was read as a
Europe PMC capability gap and nearly "fixed" working code
([debt-005](/design/implementation/debt/debt-005-fetch-is-incomplete-by-id-type.md)).
Anything measured through a warm cache needs a cold-cache control.

Cheapest useful fixes, none of them built yet: don't cache empty result sets; add
an explicit invalidation path (or a cache-bypass flag) exposed to the tools; log
when an answer is served from cache at INFO rather than DEBUG.
