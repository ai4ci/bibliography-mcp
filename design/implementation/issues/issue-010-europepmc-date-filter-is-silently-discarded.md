---
status: closed
since-version: 0.1.0
target-version: 0.3.0
tags: europepmc, search, query, dates, data-integrity
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Resolution

Fixed 2026-10-17 (developer):

- the stray `)` is gone and the clause is properly bracketed as
  `(FIRST_PDATE:[... TO ...])`. Verified live: a 2015 window now returns 2015
  records, where before it returned 25 of 25 from 2026;
- `between()` only adds a clause when a bound was actually asked for;
- `pageSize` is clamped to 1000.

**The journal term is deliberately not a field restriction.** Europe PMC's
`JOURNAL` field matches only the exact short title, so "BMJ" or "Lancet" as a
user would type it finds nothing. Sending it as free text trades precision for
recall on purpose. That decision is now a comment in `SearchFields.JOURNAL` and
is pinned by `europePmcSearchesAJournalAsFreeTextByDesign`, so it reads as a
choice rather than an oversight.

## Issue desc

**Europe PMC date windows do not work at all.** A search for 2015 returns 2026
papers. Confirmed live: `europePmcHonoursAPublicationDateWindow` got 25 of 25
records outside the requested window, the most recent dated three weeks before
the test ran.

The cause is one character. `QueryBuilder.between` builds:

    this.terms.add("FIRST_PDATE:[" + fromStr + " TO " + toStr + "])");

with a trailing `)` that has no opening bracket, giving a final query like:

    (malaria) AND FIRST_PDATE:[2015-01-01 TO 2015-12-31])

Europe PMC does not reject this. It parses what it can and drops the rest, so
the caller gets a plausible-looking unfiltered result set and no error — which
is why ad-hoc testing never caught it, and why this needed a live test that
checks the *dates of the records returned* rather than that the request
succeeded.

Three further defects in the same builder:

**The window is applied whether or not it was asked for.** `withQuery` always
calls `between(...)`, which defaults to `1900-01-01` and today. So a query with
no date constraint still acquires `FIRST_PDATE:[1900-01-01 TO <today>]`, which
silently drops anything the index dates outside that range (including records
with a future or missing first-publication date). It also means `terms` is
never empty, so the `if (this.terms.size() == 0) return Optional.empty()` guard
in `execute()` can never fire.

**A journal is not restricted to the journal field.** `SearchFields.JOURNAL` is
declared with a `null` marker, so a journal name becomes a bare `(BMJ)` term
matching anything that mentions the journal anywhere. `JOURNAL` is a real
Europe PMC field (it is in `/fields`), so the restriction is available.

**`pageSize` is not clamped.** Europe PMC caps `pageSize` at 1000; a `Query`
with a larger limit passes it straight through.

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslation.java)
[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java)

`europePmcBuildsABalancedQuery`, `europePmcLeavesAnUnaskedDateWindowAlone`,
`europePmcRestrictsAJournalTermToTheJournalField` and
`europePmcKeepsPageSizeWithinTheDocumentedMaximum` offline;
`europePmcHonoursAPublicationDateWindow` live.

## Suggested fix

1. Drop the stray `)`. The offline balanced-parenthesis assertion will keep it
   from coming back.
2. Apply the window only when `getAfter()` or `getUntil()` is present, filling
   just the missing end. Europe PMC accepts a year alone
   (`FIRST_PDATE:[2015 TO 3000]`), so an open-ended range is expressible
   without inventing a bound.
3. Give `SearchFields.JOURNAL` the marker `JOURNAL`.
4. Clamp `pageSize` to 1000.

`TITLE_ABS`, `AUTH` and the `FIRST_PDATE` field name itself are all correct and
are now covered by tests.
