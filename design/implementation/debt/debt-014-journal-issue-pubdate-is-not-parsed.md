---
class: debt
id: debt-014-journal-issue-pubdate-is-not-parsed.md
title: Journal issue pubdate is not parsed
status: open
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
