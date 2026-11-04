---
title: Shared read timeout is a trap
status: closed
sinceVersion: 0.1.0
targetVersion: 0.4.0
tags:
- api-client
- timeouts
IMPACTS:
- main:java/uk/co/terminological/bibliography/CachingApiClient.java
---
`CachingApiClient` gives every client a 3-second read timeout. That is right for most
bibliographic APIs and wrong for several of them, and the failure is silent: every
call times out, the API appears to have no data, and the symptom is an empty result
set rather than an error.

It has bitten twice:

| client | response time | symptom before the fix |
|---|---|---|
| OpenCitations | 3-6s | zero citations, from the only source that had them |
| medRxiv (`api.biorxiv.org`) | 3-6s | zero references; the JATS was never even requested |

A `setReadTimeout(int)` override now exists on the base class with a comment saying
when to use it, and both clients call it. What remains is that a new client has to
*remember*: nothing fails loudly when it does not. The arXiv API is next, and it is
rate-limited to one request every three seconds, so it is exactly the kind of client
that will meet this.

Cheapest useful steps, none built: default to a longer timeout and shorten per client
rather than the reverse; or make the timeout a constructor argument, so each client
states its expectation where it is read; or log at WARN on a timeout, so an empty
result can be told apart from a slow one.


## Closed — 0.2.0

The default is now generous and clients shorten it, rather than the reverse.

`CachingApiClient.DEFAULT_READ_TIMEOUT_MS` is **15 seconds** and
`DEFAULT_CONNECT_TIMEOUT_MS` 5. That is far longer than any bibliographic API in
this project needs when it is healthy, which is the point: a client that forgets
to raise it now pays latency on a sick API instead of silently reading an empty
result set.

This changed the clients that had already been bitten:

| client | was | now |
|---|---|---|
| OpenCitations | 30 s | 30 s — genuinely slower than the default |
| medRxiv | 30 s | 30 s — the JATS fetch is slower still |
| OpenAlex | 8 s | removed: an override *below* the default was this record upside down |
| PdfFetcher | 4 s | the shared defaults, stated explicitly — it builds its own Jersey client |

A timeout also now says so. It used to land in the same `catch` as a parse
failure and be reported as "could not parse API result", which named the wrong
cause; it is now told apart by its `SocketTimeoutException` and logged with the
timeout it exceeded, so the fix is in the message.

The arXiv API, which this record predicted would meet the trap next, is covered
by the default without saying anything.
