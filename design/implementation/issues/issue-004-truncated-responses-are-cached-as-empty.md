---
status: closed
since-version: 0.1.0
target-version: 0.3.0
tags: data-integrity, cache, europepmc
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Issue desc

A **truncated HTTP response body** is parsed as a valid empty result set and then
cached, so a broken response turns into a lasting "this record does not exist".

Diagnosed 2026-09 from a real occurrence: fetching `DOI:10.1136/bmjqs-2018-008370`
from Europe PMC returned 0 records while the same request on a cold cache returned
1, repeatedly and reproducibly.

What the cache store actually contained for that key — inspect it with
`grep -a`/python over `<cache>/<client>/file/week_*/offheap-disk-store/ehcache-disk-store.data`:

```
key  query=DOI:10.1136/bmjqs-2018-008370  resultType=core  pageSize=1000
body {"version":"6.9"}                      <- 15 bytes, nothing else

key  query=EXT_ID:30636200 AND SRC:MED    resultType=core  pageSize=1000
body {"version":"6.9","hitCount":1,"request":{...},"resultList":{...}}   <- complete
```

A complete Europe PMC response carries `hitCount`, a `request` block and the
result collections — the sibling entry shows the shape. The stored entry is a
*prefix* of that, which is the signature of a partial read, not of an empty
result set.

Mechanism, all three steps needed:

1. `BinaryData.from(InputStream)` reads the stream to EOF, so a cut-short
   connection yields a short body with no error.
2. `EuropePMCListResult.getItems()` concatenates `referenceList.reference`,
   `citationList.citation` and `resultList.result`; when none are present it
   returns an **empty stream rather than raising**, so the truncated body is
   indistinguishable from a legitimate empty result.
3. The HTTP status was 2xx, so `CachingApiClient.call` treated it as a success and
   `cache.put` stored the broken body. Europe PMC uses the 7-day `week` cache, so
   the wrong answer persists for a week; clients that call `cacheForever()`
   (CrossRef, Unpaywall, PMCIDClient, PdfFetcher) would pin it indefinitely.

**Not a data update and not ordinary API inconsistency.** The record was in the
index before the incident (an earlier run returned it) and after it (six
consecutive live calls, and a cold-cache fetch, all return it), so nothing
changed upstream. A legitimate zero-hit answer would still carry `hitCount:0` and
a `request` block — a short prefix is a different thing entirely.

Consequence: MCP tools can report "no such record" for a record that exists, with
no indication anything went wrong. A silent false negative is worse than an
error, and this one cost real diagnostic time and nearly caused working code to
be "fixed".

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/pubmedclient/TestApiDriftParsing.java)

`aTruncatedSearchResponseIsIndistinguishableFromNoResults` pins the current
behaviour: a body of `{"version":"6.9"}` yields zero items and no `hitCount`, so
the client has nothing to detect the breakage with.

## Resolution

Fixed with options A and B:

- **A** — `EuropePMCListResult.isWellFormed()` (an Europe PMC list response must
  carry `hitCount` or one of the result collections) and every Europe PMC parse
  site now throws on a body that fails it, so `CachingApiClient` treats the call
  as failed: the body is **not** cached and the next call retries.
- **B** — `CachingApiClient.isTruncated(declaredLength, contentEncoding, actual)`
  compares the bytes received with the `Content-Length` header (skipping the check
  when the body was decoded) and refuses to parse or cache a short read.

Verified against the real poisoned entry rather than a simulation:

```
Cache hit: {...query=DOI:10.1136/bmjqs-2018-008370...}
Could not parse cached result: {...}          <- rejected by the shape check
Calling url: .../search?query=DOI:...         <- entry dropped, refetched live
= 1 record, and the good body is cached in its place
```

So existing poisoned caches **self-heal** on next use; clearing them is optional.
The same truncated body was present in two independent cache directories
(`/tmp/ehcache/test125` and `~/.cache/ehcache`), so this was recurring rather
than a one-off.

Tests: `aTruncatedSearchResponseIsRecognisedAsBroken`,
`aCompleteButEmptyResponseIsWellFormed`, `aReferenceListResponseIsWellFormed`,
`aBodyShorterThanItsDeclaredLengthIsDetected`.

## Fix options

| # | Option | Effort | Notes |
|---|--------|--------|-------|
| A | Validate the document shape before using and caching it: an Europe PMC search response must carry `hitCount` (or one of the result collections); otherwise treat the call as failed — warn, do **not** cache, return empty so the next call retries | S | Fixes the actual false negative; the check is client-specific |
| B | In `CachingApiClient.call`, compare the bytes read with the `Content-Length` header (already available from the response) and treat a mismatch as a failed call, not caching it | S | General safety net for every client, but only when `Content-Length` is present (not for chunked or compressed responses) |
| C | Never cache an empty result set | S | Defensive, but every legitimate miss then re-hits the API |

**Recommendation:** A **and** B — B is the general net, A is the guarantee that
an ambiguous body is never mistaken for an answer.

## Inspecting a poisoned cache

Entries are greppable in the week store, which makes this diagnosable without
guessing: keys appear as the JSON built by `keyFromApiQuery`, bodies follow.
Clearing is safe — it is a cache: `rm -rf <cache-dir>/<client>` (or wait out the
7-day TTL, though that does not apply to `cacheForever()` clients).

Related: [debt-006](/design/implementation/debt/debt-006-cached-answers-have-no-invalidation-path.md)
(no invalidation path).
