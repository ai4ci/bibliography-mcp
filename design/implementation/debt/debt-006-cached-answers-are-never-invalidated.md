---
title: Cached answers are never invalidated
status: closed
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
`forever` (no expiration) and `week` (7-day TTL), and CrossRef, PMCIDClient
and PdfFetcher each opt in explicitly via `cacheForever()`, so an
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

## Closed — 0.2.0

Three of the record's four cheapest fixes, and the fourth deliberately not.

**Nothing is permanent.** The cache named `forever` had no expiry at all, so a
successful-but-empty answer was pinned for the life of the directory. It now has
a 180-day life. Bibliographic metadata does not change often enough to want a
short one, but "never asked again" was the actual defect in this record's title.

**Empty bodies are not cached.** `worthCaching` rejects a body that is empty or
blank, on every path into the cache — `call`, `cachedStream`, `cachedString`,
`cachedObject` — and logs that it did. An empty body is a gap, not an answer.

**There is a bypass, and the tools expose it.** `search` takes `refresh`, which
turns the cache off for that one call and always puts it back. This is the part
that closes the record's real complaint: an agent that gets a wrong answer can
now ask again, instead of depending on a human finding and deleting a directory.
It rides on the existing `disableCache()`, which already removes the entry and
refetches, so it refreshes rather than merely skips.

**Cache hits are still logged at DEBUG, not INFO.** The record suggests raising
them so a cached answer can be told from a fresh one. Every call is a cache hit
in normal running, so at INFO the log would be nothing else, and the thing it
would be used for — "ask that again properly" — is now a parameter rather than
an inference from the log.

What this does **not** do is detect a well-formed response that happens to
contain no results, which is what the reproduction above actually was. Zero-ness
is a per-API shape — `hitCount`, an empty `results` array, an absent `message` —
and a generic client cannot see it without each client declaring it. The 180-day
life bounds how long such an answer survives and `refresh` ends it on demand;
making each client declare its own emptiness test is a separate change and has
not been made.

Its other consequence stands and is worth keeping in view: **anything measured
through a warm cache needs a cold-cache control.** That is what made this
record's reproduction look like a Europe PMC capability gap
([debt-005](/design/implementation/debt/debt-005-fetch-is-incomplete-by-id-type.md)).

An existing cache was written with no expiry at all. If EHCache objects to being
reopened under a different one, the directory is cleared and rebuilt by
[debt-007](/design/implementation/debt/debt-007-cache-stores-break-on-cached-class-changes.md),
which landed in the same pass — the cost is the saved calls, not a server that
will not start.
