---
status: closed
since-version: 0.1.0
target-version: 0.3.0
tags: entrez, search, query, dates
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Resolution

Fixed 2026-10-17 (developer). `since()`/`until()` are replaced by a single
`between(Optional<LocalDate>, Optional<LocalDate>)` that sets `datetype=pdat`
and always supplies both `mindate` and `maxdate`, filling a missing end with
1900-01-01 or today. The one-sided builder methods are gone, so the trap they
represented cannot be reached.

Verified live: eSearch now echoes the restriction in its own query
translation, `... AND 2015/01/01:2015/12/31[Date - Publication]`, which is what
`entrezHonoursAPublicationDateWindow` asserts.

Cosmetic leftover, not worth its own issue: `between()` adds `datetype` twice,
so the request carries `datetype=pdat&datetype=pdat`. Checked against the live
API -- the duplicate is ignored and results are identical with one or two
copies -- so this is untidy rather than wrong.

## Issue desc

`EntrezClient.ESearchQueryBuilder` filters a publication-date window with
`datetype=edat`, and sends one-sided ranges that eSearch ignores outright.

**Wrong date field.** `since()` and `until()` both set `datetype=edat`. Per the
E-utilities spec, `edat` is the Entrez Date — "the date the citation was added
to PubMed, except for records added more than twelve months after the date of
publication" — whereas `pdat` is "the article's publication date".
`Query.getAfter()`/`getUntil()` are a publication window (a `Query` year is a
publication year), so `pdat` is the correct field.

For recent literature the two dates are close enough that this does not show up
— the live test `entrezHonoursAPublicationDateWindow` passes against a 2015
window — which is exactly why it needs an offline test against the spec. It
diverges for anything indexed retrospectively, and for older material it
diverges badly.

**One-sided ranges are dropped.** The spec is explicit: "You must include both a
`mindate` and a `maxdate` to specify a date range." `withQuery` calls `since`
and `until` independently, so a `Query` carrying only `getAfter()` produces a
`mindate` with no `maxdate`, and eSearch ignores the constraint entirely. The
caller gets unfiltered results and no error. (A `Query` built from `year` always
has both, so this only bites callers who set the bounds directly — which the MCP
`search` tool's date range does.)

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslation.java)

`entrezFiltersOnPublicationDateNotEntrezDate` and
`entrezAlwaysSendsBothEndsOfADateRange`.

## Suggested fix

Use `datetype=pdat` in both `since()` and `until()`. For the one-sided case,
fill the missing end in `withQuery` rather than in the builder methods — an open
start becomes a very early date and an open end becomes today — so that the
single-sided builder methods keep their literal meaning for callers using them
directly.

Note that eSearch accepts `YYYY`, `YYYY/MM` and `YYYY/MM/DD`, so the existing
`yyyy/MM/dd` format is correct and needs no change.

Everything else this builder does is correct and is now covered:
`(term[Title/Abstract])`, `(name[Author])`, `(journal[Journal])`,
`(term[Text Word])`, ANDed together, with the limit as `retmax`.
