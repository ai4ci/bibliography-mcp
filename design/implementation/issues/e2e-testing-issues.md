---
title: End to end bug report from a live MCP session
status: closed
sinceVersion: 0.0.6
targetVersion: 0.1.0
tags:
- mcp
- reported-externally
- triage
---

# End to end bug report from a live MCP session

A report from someone using the server over MCP, covering five complaints in one
unnumbered message. It is kept as the record of what was observed, and closed by
being split: each complaint now has a numbered record or a roadmap idea, listed
below, so it can be tracked, targeted and rendered like everything else. Kept
because the observations (the exact JSON returned, the queries that came back
empty) are evidence those records refer to and would otherwise be lost.

## Triage

| Complaint | Where it went |
|-----------|---------------|
| 1. Author/creator metadata absent from every returned record | [issue-018](/design/implementation/issues/issue-018-tool-output-drops-authors-and-print-details.md) — confirmed: `recordJson` never emits authors, and volume/issue/pages are not on `Record` at all |
| 2. `citation_styles` lists 9,500 styles, nothing renders them | [issue-019](/design/implementation/issues/issue-019-citation-styles-cannot-be-selected.md) — confirmed: `Record.render` exists, its only setter is `@Deprecated` with no caller |
| 3. Entrez returns `[]` for every query; Crossref returns nothing for author+title strings | Entrez: [issue-009](/design/implementation/issues/issue-009-entrez-date-window-filters-the-wrong-date.md) and [issue-014](/design/implementation/issues/issue-014-entrez-record-dates-are-not-publication-dates.md). Crossref: [issue-008](/design/implementation/issues/issue-008-crossref-query-translation-is-broken.md). Re-tested live (2026-11-02): both APIs answer a plain query fine, so what failed was the *translated* query, not the API — and the fix for mixed intent is idea 8 |
| 4. `search` with no `apis` returns `[]`; with two APIs returns only Europe PMC | [issue-013](/design/implementation/issues/issue-013-one-failing-api-empties-a-multi-api-search.md) for the empty result (one client throwing loses everything), [plan-008](/design/implementation/plans/plan-008-search-deduplication.md) for the merging and provenance-tagged unified hits it asked for |
| 5. Raw JSON is too large; wants structured query parameters; overlap with a direct Zotero tool | Ideas [7](#7-return-formatted-text-not-raw-json-from-the-record-tools--proposed-effort-s) and [8](#8-expose-the-structured-query-over-mcp--proposed-effort-s) in the [roadmap](/design/roadmap/index.md). The Zotero overlap is not a defect: two servers over the same store with different jobs, and the bridge's write barrier is deliberate (feature-005) |

Complaint 4's third bullet — de-duplicate across providers and tag confirming
sources — is the one part of the report that was already in flight, which is
worth noting because the reporter described the symptom before the feature
existed to fix it.

## Original report

### 1. Author/creator metadata dropped in normalised records

`biblio_fetch`, `biblio_search` and `biblio_session_records` all return records
with `title`, `journal`, `date`, identifiers and provenance, and no author or
creator field, for any provider. Crossref additionally supplies `volume`, `issue`
and `page` which are likewise absent. Example returned object:

```json
{"id": "10.1136/bmjqs-2018-008370", "idType": "DOI",
 "title": "Artificial intelligence, bias and clinical safety.",
 "journal": "BMJ Quality & Safety", "date": "2019-01-12",
 "PMID": "30636200", "DOI": "10.1136/bmjqs-2018-008370", "PMCID": "6560460",
 "publicationType": "article-journal",
 "provenance": {"source": "europepmc",
   "nativeType": "Research Support, Non-U.S. Gov't; research-article; Journal Article"}}
```

### 2. Citation rendering tool missing

`biblio_citation_styles` returns ~9,481 style names and its description says
"for rendering references"; no tool accepts a style and renders a record.

### 3. Search anomalies

Entrez returns `[]` for everything tried, including `asthma`, an exact DOI, a
bare PMID, `30636200[UID]` and `Challen R[Author]`. Crossref returns `[]` for
`Challen artificial intelligence safety` and for a quoted title plus author, but
20 results for the quoted title alone, with the target paper at position 17.

### 4. Default search behaviour

`biblio_search` with no `apis` argument always returns `[]`, while
`biblio_collect_search` with the same query collects 4 records, all from Europe
PMC; `apis: ["crossref", "europepmc"]` likewise yields only Europe PMC. Expected:
search a sensible default set, de-duplicate by DOI/PMID, return merged hits with
provenance showing which sources confirmed each.

### 5. Output shape and the direct Zotero tools

Records as JSON cost tens of kilobytes per search and are not directly usable in
prose; the report asks for Markdown by default with a `format` escape hatch, for
a structured query object (`title`, `author`, `year`, `doi`, `journal`) compiled
per backend, and for a session citation style so records arrive pre-formatted. It
also compares this server's Zotero bridge with a direct Zotero MCP server: the
direct one has CRUD, export and attachments, this one has the citation graph,
federation, cleanup reporting and topic modelling — with lossy import (authors
again) and a write barrier as the friction.
