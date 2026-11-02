---
class: issue
id: issue-023-identifier-queries-are-text-searched.md
title: Identifier queries are text searched
status: closed
sinceVersion: 0.0.6
targetVersion: 0.0.11
tags:
- bug
- search
- mcp
TESTED_BY:
- test:java/uk/co/terminological/bibliography/api/TestIdentifierSearch.java
---
## Issue desc

Searching for a DOI returned the papers that *mention* it, not the paper it names.

`BiblioFacade.search` sent every query to the text-search APIs, so
`10.1136/bmj.n579` was matched against indexed text. Measured live:

| API | records for `10.1136/bmj.n579` |
|---|---|
| OpenAlex | 199 returned of **305 matching** (`default.search` is full text, so every citing paper matches) |
| Crossref | 20 |
| Europe PMC | 2 |
| Entrez | 1 |
| **deduplicated** | **220** |

The caller wanted one paper. Worse, the dedup then has to resolve every
identifier of all 220, which measured 11.7 s of identifier-mapping calls against
2.4 s for a bounded query — so the wrong answer was also the slow one.

This was hiding in plain sight: `TestSearchDedupLive.knownDoiReturnsResults`
asserted `results.size() <= 3` for exactly this query. The assertion was right
about the intent and the class had never run (it read a secrets path that exists
nowhere), so nothing reported it.

## Fix

An identifier does not describe a work, it names one, so a query carrying nothing
but an identifier is answered by `fetch`:

- **Recognised**: a DOI bare, as `doi:…`, or as a `doi.org` URL; a PMCID; an
  OpenAlex work id; `pmid:NNNN`; and a bare number of 7–8 digits.
- **Not recognised**: shorter bare numbers. `2018` is a year somebody typed into
  a search box, and reading it as a PMID would answer an ordinary search with
  nothing.
- **Still a search**: an identifier alongside an author, a journal or a date
  window. That caller is filtering, and a fetch cannot honour the rest of what
  they asked for.
- **No fallback.** A mistyped DOI finds nothing and says so. Falling back to a
  text search would bring back the 220 results this issue is about, at the moment
  the caller is least able to tell they are wrong.

`McpTools`'s `search` description says so, because an agent cannot see this
behaviour otherwise.

Pinned by `TestIdentifierSearch` (11 offline tests) and
`TestSearchDedupLive.aDoiFindsTheOnePaperItNames`, all written and proven to fail
first.
