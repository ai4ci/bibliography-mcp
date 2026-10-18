---
status: open
since-version: 0.1.0
target-version: 0.3.0
tags: facade, search, resilience
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

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

[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java)

`theSameQueryRoutedToEveryApiReturnsSomethingFromEachOfThem` fails with the
Crossref NPE, demonstrating that the Entrez, Europe PMC and medRxiv results are
lost with it.

## Suggested fix

Catch per API inside the loop, log the failure against the API name that caused
it (the loop already logs a per-API hit count, so the seam is there), and carry
on with the rest. Then decide whether the failure should also be *reported* to
the caller rather than only logged: an agent that silently receives three
APIs' worth of results when it asked for four cannot tell that coverage was
reduced, which matters for a tool whose answers feed a literature search.

Worth considering a small result wrapper carrying per-API status alongside the
records, rather than a bare collection — but that changes the `Searcher`
contract, so it needs a decision rather than an implementation.
