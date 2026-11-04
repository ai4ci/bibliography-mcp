---
class: debt
id: debt-014-journal-issue-pubdate-is-not-parsed.md
title: Journal issue pubdate is not parsed
status: closed
sinceVersion: 0.1.0
targetVersion: unassigned
tags:
- entrez
- dates
- parsing
- model
---
## Debt desc

`EntrezEntry.getDate()` prefers `Article/ArticleDate` and falls back to
`PubMedPubDate[@PubStatus='pubmed']` — the date PubMed received the record —
skipping the middle option, `Article/Journal/JournalIssue/PubDate`.

This is the remnant of
[issue-014](../issues/issue-014-entrez-record-dates-are-not-publication-dates.md),
whose substantive defect is fixed. What is left is small and measured: across
four decades of records that carry no `ArticleDate`, the received date lands in
the right year and within about a month of the issue date.

| PMID | getDate() | issue PubDate |
|---|---|---|
| 13678 | 1976-07-01 | 1976 |
| 8554176 | 1995-10-01 | 1995 Oct |
| 15841083 | 2005-04-21 | 2005 May |

## Why it is a design question rather than a parser

`PubDate` does not always hold a date. It can be:

- a bare year — `2019`
- a year and a month name — `2019 Jun`
- a `MedlineDate` string — `2019 Jun-Jul`, `1998 Dec-1999 Jan`

`Record.getDate()` returns `Optional<LocalDate>`, which can hold none of those
honestly: parsing `2019` into `2019-01-01` invents a day and a month, and a
reader cannot tell an invented January from a real one. The same question
applies to every source with partial dates, so answering it here means deciding
what the model does about precision — a field alongside the date, a partial-date
type, or a documented convention — and that is a change to `Record`, not to
`EntrezEntry`.

## Suggested fix

Decide the precision question first. If the answer is "invent nothing", the
fallback order stays as it is and this record closes as *won't fix* with the
reasoning kept. If the model grows a way to say "2019, month unknown", then
`PubDate` becomes the middle fallback and the received date moves to last.

Until then the behaviour is correct for the common case and imprecise by about a
month for pre-electronic material, which is better than the alternative of a
confident wrong day.

## Closed — 0.2.0 — won't fix, with the reasoning kept

The decision, taken deliberately: **invent nothing**.

`Record.getDate()` returns `Optional<LocalDate>`, and `LocalDate` cannot say
"2019, month unknown". Parsing `2019` into `2019-01-01` invents a day and a
month; a reader of the merged record cannot tell an invented January from a real
one, and nothing downstream — Zotero, the citation renderer, the RRF ranking —
knows the difference either. A model that grows a precision field or a
partial-date type is a change to `Record` that every source and every consumer
pays for, made here for the benefit of pre-electronic Entrez records measured at
roughly a month's imprecision.

Until such a type exists, the fallback order stays as it is: `ArticleDate`
first, then the received date. The behaviour is correct for the common case —
every modern record carries an `ArticleDate` — and imprecise by about a month
for material old enough not to, which the record's own table shows. That is
better than a confident wrong day.

The measurement stays because it is the argument: three PMIDs, three fallback
dates, all landing in the right year, the worst within a month.

Reopening this is a model decision, not an Entrez one: if a partial-date type
ever lands in `Record`, `PubDate` becomes the middle fallback and the received
date moves to last, and the parsing itself is an afternoon.
