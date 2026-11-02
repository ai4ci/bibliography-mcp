---
title: Unpaywall preprint doi not resolved
status: closed
sinceVersion: 0.0.6
targetVersion: 0.1.0
tags:
- maintenance
- api-drift
- timeout
- unpaywall
TESTED_BY:
- test:java/uk/co/terminological/bibliography/api/openalex/TestOpenAlexClientEndToEnd.java
---

## Issue desc

`TestUnpaywallClientEndToEnd.testRetrieveAndParsePreprintByDoi` fails: fetching
the preprint DOI `10.64898/2025.12.12.25342129` via `UnpaywallClient.fetch()`
returns 0 records. A direct curl call against the same API endpoint with the
same developer email returns the expected full record (HTTP 200, `is_oa: true`,
`genre: posted-content`, 16 authors). The failure is therefore in the Java
client's HTTP transport, not the availability of the record.

The cause is the shared 3 second read timeout, not the URL encoding: Unpaywall
answers this DOI in 3.5-4.6 seconds when the record is not already warm. See
[debt-009](/design/implementation/debt/debt-009-shared-read-timeout-is-a-trap.md)
— this is the third client to be bitten, after OpenCitations and medRxiv.


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

Both the raw-slash and `%2F`-encoded forms work from curl, and both answer in
3.5-4.6 s for this DOI (0.4 s once warm, for either form).

### Root cause: the shared read timeout, established 2026-11-02

Running the live test with `debugMode()` on a cold cache directory shows the
exception the transport swallows:

```
com.sun.jersey.api.client.ClientHandlerException: java.net.SocketTimeoutException: Read timed out
    at uk.co.terminological.bibliography.CachingApiClient.call(CachingApiClient.java:397)
    at uk.co.terminological.bibliography.api.unpaywall.UnpaywallClient.getUnpaywallByDoi(UnpaywallClient.java:108)
PROBE preprint: false
PROBE known: true
```

`CachingApiClient` sets `setReadTimeout(3000)` for every client, and
`UnpaywallClient` is one of the clients that never overrides it. A
`SocketTimeoutException` is caught by the general `catch (Exception e)` at
`CachingApiClient.java:438` and becomes `Optional.empty()`, so a slow correct
answer is indistinguishable from "no such record". The same call with a warm
cache or a shorter response succeeds, which is why the second DOI in the same
run returned a record while the first did not.

The hypotheses in an earlier version of this record are withdrawn — each was
checked and none holds:

- **Path re-encoding.** Not the cause. Both encodings return 200, and
  double-encoding (`%252F`) returns 404 for both Crossref and Unpaywall, so the
  client's `encode()` is behaving as the API expects.
- **Singleton state poisoning.** Not the cause. The probe above used a fresh
  cache directory and a freshly built client, and still failed.
- **Rate-limiter exhaustion.** Not the cause. The call was reached and timed out
  in the socket read, three seconds later.

### Impact

- One live test always fails, reducing confidence in the live tier.
- The preprint DOI used in the Zotero compare fixture is no longer testable
  through Unpaywall.
- Other production `getUnpaywallByDoi()` calls for this DOI would also fail
  silently (return empty), potentially affecting cross-API deduplication.

### Suggested fix

`this.setReadTimeout(30_000)` in the `UnpaywallClient` constructor, as
`OpenCitationsClient` already does. The general fix — a per-API timeout in
configuration rather than a constant in the shared base — is debt-009.

## Resolution (2026-11-02)

Not fixed — **retired**. `UnpaywallClient` and its tests are deleted; OpenAlex
now supplies the open-access location, and the preprint this record could not
fetch is the assertion that proves the replacement:
`TestOpenAlexClientEndToEnd.theOpenAccessPdfIsInTheSamePayloadAsTheMetadata`
resolves `10.64898/2025.12.12.25342129` and expects the medRxiv pdf URL,
arriving in the same payload as the metadata rather than in a second call to a
second provider.

The root cause stands as a lesson for debt-009: a slow API behind a shared
3-second timeout does not fail, it returns "nothing". Unpaywall was the source
that proved it. `MergePolicy`'s PDF priority now names `openalex` where it named
`unpaywall`.


[TestUnpaywallClientEndToEnd.java](/src/test/java/uk/co/terminological/bibliography/api/unpaywall/TestUnpaywallClientEndToEnd.java)
