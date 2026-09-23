---
status: open
since-version: 0.1.0
target-version: 0.3.0
tags: cache, data-integrity
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: since-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Debt desc

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
`forever` (no expiration) and `week` (7-day TTL), and some calls opt in
explicitly via `cacheForever()`, so an empty result can be pinned indefinitely.

Consequence: the MCP tools can report "nothing found" for something that exists,
with no indication that the answer came from a cache rather than the API. This is
worse than a failure, which at least surfaces.

It also produced a false lead: a measurement against the stale cache was read as a
Europe PMC capability gap and nearly "fixed" working code
([debt-005](/design/implementation/debt/debt-005-fetch-is-incomplete-by-id-type.md)).
Anything measured through a warm cache needs a cold-cache control.

Cheapest useful fixes, none of them built yet: don't cache empty result sets; add
an explicit invalidation path (or a cache-bypass flag) exposed to the tools; log
when an answer is served from cache at INFO rather than DEBUG.

## Impacts

- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/CachingApiClient.java)
