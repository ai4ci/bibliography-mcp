---
status: open
since-version: 0.0.6
target-version: 0.1.0
tags: maintenance, api-drift, unpaywall
---

## Issue desc

`TestUnpaywallClientEndToEnd.testRetrieveAndParsePreprintByDoi` fails: fetching
the preprint DOI `10.64898/2025.12.12.25342129` via `UnpaywallClient.fetch()`
returns 0 records. A direct curl call against the same API endpoint with the
same developer email returns the expected full record (HTTP 200, `is_oa: true`,
`genre: posted-content`, 16 authors). The failure is therefore in the Java
client's HTTP transport, not the availability of the record.


### Observed behaviour

```
TestUnpaywallClientEndToEnd.testRetrieveAndParsePreprintByDoi:59
  Expected 1 fetched record for preprint DOI 10.64898/2025.12.12.25342129
  expected:<1> but was:<0>
```

- `getUnpaywallByDoi("10.64898/2025.12.12.25342129")` returns `Optional.empty()`
- `getUnpaywallByDoi("10.1136/bmjqs-2018-008370")` also starts returning `Optional.empty()`
  after earlier calls in the same session — suggests a shared-client state
  problem (singleton rate-limiter exhaustion? singleton cache poisoning?)

### Evidence the API still serves the record

```shell
curl "https://api.unpaywall.org/v2/10.64898/2025.12.12.25342129?email=..."  # → 200, full JSON
curl "https://api.unpaywall.org/v2/10.64898%2F2025.12.12.25342129?email=..."  # → 200, same
```

Both the raw-slash and `%2F`-encoded forms work from curl.

### Suspected root cause

`UnpaywallClient.getUnpaywallByDoi(doi)` builds the URL as
`"https://api.unpaywall.org/v2/" + encode(doi)` where `encode()` uses
`URLEncoder.encode(doi, "UTF-8")`, producing `%2F` in the DOI path (e.g.
`10.64898%2F2025.12.12.25342129`). Jersey 1.x (`WebResource`) decodes `%2F`
back to `/` before sending, so the actual HTTP request path is
`/v2/10.64898/2025.12.12.25342129`.

While Unpaywall's server handles both forms from curl, the Jersey client's
behaviour (or the way the response is processed) differs. Possibilities:

1. **Path re-encoding mismatch**: Jersey re-encodes the path before the request,
   potentially producing a different encoding that the server interprets
   differently.
2. **Singleton state poisoning**: `UnpaywallClient` uses a static singleton map
   keyed by email. If a prior call cached a failed response (e.g. a 422 from
   a transient misconfiguration), the singleton returns the cached empty result
   for the entire JVM lifetime, and the `cacheForever()` policy prevents
   revalidation.
3. **Rate-limiter exhaustion**: The token-bucket rate limiter may block the
   call silently.

### Impact

- One live test always fails, reducing confidence in the live tier.
- The preprint DOI used in the Zotero compare fixture is no longer testable
  through Unpaywall.
- Other production `getUnpaywallByDoi()` calls for this DOI would also fail
  silently (return empty), potentially affecting cross-API deduplication.

### Suggested fix

1. **Confirm** whether clearing the persistent Ehcache under `/tmp/ehcache/` and
   isolating the `UnpaywallClient` singleton (e.g. creating a fresh instance
   rather than reusing the singleton) resolves the issue.
2. **If singleton caching is the cause**: either remove the singleton pattern
   (UnpaywallClient has no expensive setup besides the cache path), or add a
   `flushCache()` / `withNoCache()` method for test use.
3. **If Jersey path encoding is the cause**: construct the DOI URL without
   encoding the slash — pass the raw DOI into the URL path directly, i.e.
   remove the URLEncoder call for the DOI portion.

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/api/unpaywall/TestUnpaywallClientEndToEnd.java)
