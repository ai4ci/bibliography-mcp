---
title: One failing api empties a multi api search
status: closed
sinceVersion: 0.1.0
targetVersion: 0.2.0
tags:
- facade
- search
- resilience
TESTED_BY:
- test:java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java
- test:java/uk/co/terminological/bibliography/api/TestSearchDedup.java
---
## Issue desc

`BiblioFacade.multiSearch` loops over the configured search APIs with no error
handling:

    searchByApi.forEach((searchApi, search) -> {
        Collection<? extends Record> tmp = Collections.emptyList();
        if (searchApi.equals("crossref")) {
            tmp = this.api.getCrossref().search(search);
        } else if ...
        out.addAll(tmp);
    });

Any exception from any client propagates out of the whole method, so a single
misbehaving API loses the results of all the others rather than degrading the
search. Crossref is first in the default list, which is how issue-008 — a
one-line `return null` in one client — became a total search outage rather than
a missing fourth of the results.

The same shape applies to the citation and fetch loops (`citesApiLoop`,
`citedByApiLoop`, the fetch loop), which have the same structure.

This is a design gap rather than a coding mistake, and it is the reason several
of the other issues found in this round were invisible until something threw:
there is no point at which a per-API failure is reported as such.

## Why it matters

These are third-party network services. Rate limiting, transient 5xx,
scheduled maintenance and endpoint drift are normal operating conditions, not
exceptional ones — issue-002 already records endpoint drift as an ongoing
concern. An agent asking for a search should get the results that are available
plus a note about what failed, which is also what the tool contract implies by
reporting per-API hit counts.

## Reproduced by

[TestQueryTranslationLive.java](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java)

`theSameQueryRoutedToEveryApiReturnsSomethingFromEachOfThem` fails with the
Crossref NPE, demonstrating that the Entrez, Europe PMC and medRxiv results are
lost with it.


Catch per API inside the loop, log the failure against the API name that caused
it (the loop already logs a per-API hit count, so the seam is there), and carry
on with the rest. Then decide whether the failure should also be *reported* to
the caller rather than only logged: an agent that silently receives three
APIs' worth of results when it asked for four cannot tell that coverage was
reduced, which matters for a tool whose answers feed a literature search.

Worth considering a small result wrapper carrying per-API status alongside the
records, rather than a bare collection — but that changes the `Searcher`
contract, so it needs a decision rather than an implementation.

## Closed — 0.2.0

Both halves: a failure no longer empties the call, and it is no longer invisible.

**Every loop survives one source failing.** `search`, `multiSearch`, the cites
and citedBy loops, `fetch`, `fetchBySource` and `mappings` each run a source's
part through one helper that catches, logs against the API that caused it, and
carries on. The per-API dispatch came out into `dispatchSearch`, `citesFrom`,
`citedByFrom`, `fetchFrom` and `mappingsFrom`, which is also what makes a test
able to break exactly one source.

**The failure reaches the caller**, which the record left as a decision. The
`Searcher` SPI is unchanged — a single client still answers with records alone —
and the facade's own `scoredSearch` now returns a `SearchOutcome`: the ranked
records, and a list of the sources that could not be asked with the reason each
gave. `search` delegates to it, so there is one resilient path rather than two
that can drift.

The `search` tool returns a bare array of records when every source answered,
and an object with `records`, `failures` and a note when one did not — so the
common case keeps its shape and a degraded search is visibly a different thing.

**And a refused call is audible.** `CachingApiClient` turned every non-2xx into
`Optional.empty()` with a debug line, so a rate limit, a 5xx and a moved
endpoint all read as "no results". That line is now WARN. The exception-free
contract is deliberate and stays; the silence was the defect, which is the same
lesson as [debt-013](../debt/debt-013-record-render-swallows-its-failures.md).

Six offline tests, proven to fail first: a failing API does not empty a search, a
fetch or a citation lookup; the failure is reported with its API and reason; a
search where everything fails says so rather than looking empty; and a search
where nothing fails reports no failures.

### Measured against the thing that prompted it

This was written after Crossref's `return null` turned one client's defect into
a total search outage. The same shape bit a live test twice in this release —
Crossref answering 0 instead of 20 — which is now visible as a warning rather
than as a quietly smaller result set.
