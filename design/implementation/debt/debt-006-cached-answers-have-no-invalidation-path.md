---
status: open
since-version: 0.1.0
target-version: 0.3.0
tags: cache
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: since-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Debt desc

There is no way to invalidate a cached answer, and empty answers are cached like
any other, so a wrong answer sticks until it expires:

- some callers opt in to a cache with **no expiry at all** (`cacheForever()` in
  CrossRef, Unpaywall, PMCIDClient, PdfFetcher, GoogleScholar, PdfFetcher), while
  everything else lands in the 7-day `week` cache (`permanentCache()` is
  `forever` for callers that ask for it);
- the MCP tools expose no cache bypass or invalidation, so an agent that gets a
  wrong answer cannot ask again rather than relying on a human to clear a
  directory;
- cache hits are logged at DEBUG, so a "not found" gives no hint that it came
  from a cache rather than the API.

This is a contributing factor to
[issue-004](/design/implementation/issues/issue-004-truncated-responses-are-cached-as-empty.md)
(a truncated body gets cached) — validation would stop the bad entry being
written, but a bypass would also let a caller recover without deleting files.

Cheapest useful steps, none built: an invalidation or bypass option on the fetch
path; log cache hits at INFO or surface them in tool output; consider not caching
empty result sets.

## Impacts

- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/CachingApiClient.java)
