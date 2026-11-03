---
title: Scope
status: final
targetVersion: unassigned
tags:
- scope
---

# Bibliographic API client — MCP server

A Java MCP (Model Context Protocol) server exposing clients for multiple
bibliographic APIs (Entrez/PubMed, CrossRef, Europe PMC, OpenCitations,
OpenAlex, medRxiv/bioRxiv, PMC IDConverter) behind a
unified record format, plus a plain-Java facade over the same functionality.

This document says what the project is. What is *built* and what is *intended*
drift apart fast enough that keeping two lists out of date is worse than having
one, so the feature list is generated: see
[design/INDEX.md](/design/INDEX.md), whose table `loom render` fills from the
`features_by_state` view. What ships *when* is decided in the
[roadmap](/design/roadmap/index.md), which is also where an idea lives before it
becomes a feature, plan, issue or debt record.

## Supported APIs

| API | Client | Role |
|-----|--------|------|
| CrossRef | `api/crossref/CrossRefClient` | search, fetch by DOI, citation list |
| Entrez / PubMed | `api/entrez/EntrezClient` | search, fetch by PMID/PMCID |
| Europe PMC | `api/europepmc/EuropePMCClient` | search, fetch, both citation directions |
| OpenCitations Index v2 | `api/opencitations/OpenCitationsClient` | citation edges by DOI |
| OpenAlex | `api/openalex/OpenAlexClient` | search, fetch, both citation directions, identifier mapping, open-access PDF location |
| medRxiv / bioRxiv | `api/medrxiv/MedrxivClient` | preprint search and fetch |
| PMC ID Converter | `api/pmcidconv/PMCIDClient` | DOI ↔ PMCID ↔ PMID mapping |
| — | `PdfFetcher`, `PdfSink` (root package) | download an open-access pdf to a file |

## Retired

* **Reference extraction from PDF content** (cermine). PDFs are not central to
  this project — what it needs from one is that it exists and can be attached —
  and cermine is published only by a repository whose TLS certificate expired,
  so depending on it meant a build no clean machine could complete
  ([issue-025](/design/implementation/issues/issue-025-cermine-is-unfetchable-on-a-clean-build.md)).
  Retired in 0.2.0 along with `references_from_pdf`, `PdfFetcher.extractArticleRefs`
  and `BiblioFacade.getReferencesFromPdf`. A reference list for a work is better
  asked of an API that already has one — which is what the crawl does.

## Not in scope

* Bibliographic data of our own: every record is someone else's, retrieved and
  merged, never authored. See [debt-011](/design/implementation/debt/debt-011-unused-record-model-surface.md)
  for the model surface that exists for a need nothing has yet.
* A reference manager. Writing into a curated library is limited to what
  [feature-005](/design/features/feat-005-zotero-curated-repository-bridge.md)
  describes.
* Client libraries for other languages. The MCP server is the interface for
  anything that is not Java.
