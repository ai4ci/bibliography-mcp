---
title: Entrez record dates are not publication dates
status: open
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- entrez
- model
- dates
- parsing
TESTED_BY:
- test:java/uk/co/terminological/bibliography/api/entrez/TestEntrezExtraction.java
---

## Issue desc

An `EntrezEntry` reports two different dates through the `Record` and
`PrintReference` interfaces, and neither is the date PubMed itself treats as
the publication date.

| accessor | reads | means |
|---|---|---|
| `getDate()` | `PubMedPubDate[@PubStatus='pubmed']` | when the record entered PubMed |
| `getYear()` | `Article/Journal/JournalIssue/PubDate/Year` | the journal **issue** year |
| *(nothing)* | `Article/ArticleDate` | the electronic publication date |

PubMed's own `pdat` filter uses the earliest publication date, which for an
ahead-of-print article is the `ArticleDate`. The client parses that nowhere.

A worked example, found while testing the date-window query. PMID 31285980 is
returned by a `pdat` 2015 search, correctly:

- `ArticleDate` = 2015-03-23 (electronic publication) — what `pdat` matched
- `JournalIssue/PubDate` = 2019 Jun 25 — what `getYear()` returns
- `PubMedPubDate[pubmed]` = 2019-07-10 — what `getDate()` returns

So a correct query produces records whose exposed dates are four years away
from the window that selected them. On the recorded fixture the same two
accessors differ by only a day (2026-03-26 versus 2026-03-27), which is why
this has not been noticed: the gap is invisible on recent material and large on
anything published ahead of print or indexed retrospectively.

## Why it matters

`Record.getDate()` is the publication date as far as everything downstream is
concerned. It feeds the `DATE` entry in `MergePolicy`, where Entrez is ranked
second; the Zotero cleanup report, which compares an item's `date` field
against the APIs and will report a spurious conflict; and anything an agent
filters or sorts by date. A systematically late date also loses date conflicts
to sources that report the real one, so the wrong value wins quietly.

It also cost a test: `entrezHonoursAPublicationDateWindow` originally asserted
that returned records fell inside the requested window and failed, which looked
like a broken query but was a correct query plus a misreported date. It now
asserts against PubMed's echoed query translation instead, which is evidence
about the query rather than about the parsing.

## Reproduced by

[TestEntrezExtraction.java](/src/test/java/uk/co/terminological/bibliography/api/entrez/TestEntrezExtraction.java)

`theRecordDateIsWhenTheWorkWasPublished` pins `getDate()` against the recorded
`fixtures/entrez/efetch-pmid.xml`, whose `Article/ArticleDate` is 2026-03-26
while the `PubMedPubDate[pubmed]` fallback is 2026-03-27 — one day apart, so
the test passes either way and proves only that the `ArticleDate` branch is
reached. The four-year gap in the worked example above is the case no test
covers, because it needs a record with no `ArticleDate`.


## Suggested fix

Make `getDate()` the earliest publication date, matching what `pdat` means:
prefer `Article/ArticleDate` where present, fall back to
`Article/Journal/JournalIssue/PubDate`, and use `PubMedPubDate[pubmed]` only as
a last resort. Keep `getYear()` as the issue year — the print year is a
different and legitimate thing, and `PrintReference` is the right place for it.

`PubDate` needs care: it is not always a full date. It can be a year alone, a
year plus a month name (`2019 Jun`), or a `MedlineDate` string such as
`2019 Jun-Jul`, so the parser has to cope with partial precision rather than
assuming day resolution.

## Resolution

Partly fixed 2026-11-02, so this stays open with a narrowed scope.

`EntrezEntry.getDate()` now prefers `Article/ArticleDate` — the electronic
publication date, and the one PubMed's `pdat` filter matches — falling back to
`PubMedPubDate[@PubStatus='pubmed']`. On the recorded fixture the fixture's
`ArticleDate` is 2026-03-26 and that is what `getDate()` returns, so
`theRecordDateIsWhenTheWorkWasPublished` passes; its in-file comment still says
"Still failing" and is wrong (the 2026-03-27 value it describes is the
`PubMedPubDate[pubmed]` fallback at `fixtures/entrez/efetch-pmid.xml:279`, now
taken only when there is no `ArticleDate`).

What remains is the middle tier of the suggested chain, deliberately left
unimplemented at `EntrezEntry.java:133-139`: `Article/Journal/JournalIssue/PubDate`
carries partial precision — a bare year, `2019 Jun`, or a `MedlineDate` like
`2019 Jun-Jul` — which `Optional<LocalDate>` cannot represent. Records with no
`ArticleDate` therefore still report the PubMed entry date. Closing this needs
a decision about how partial dates are modelled, not just a parser.
