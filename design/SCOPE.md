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
OpenAlex, medRxiv/bioRxiv, PMC IDConverter, Cermine PDF parsing) behind a
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
| PMC ID Converter | `api/pmcid/PMCIDClient` | DOI ↔ PMCID ↔ PMID mapping |
| Cermine | `api/cermine/PdfFetcher` | parse references out of a PDF |

## Not in scope

* Bibliographic data of our own: every record is someone else's, retrieved and
  merged, never authored. See [debt-011](/design/implementation/debt/debt-011-unused-record-model-surface.md)
  for the model surface that exists for a need nothing has yet.
* A reference manager. Writing into a curated library is limited to what
  [feature-005](/design/features/feat-005-zotero-curated-repository-bridge.md)
  describes.
* Client libraries for other languages. The MCP server is the interface for
  anything that is not Java.
