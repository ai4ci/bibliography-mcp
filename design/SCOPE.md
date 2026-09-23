# Bibliographic API client — MCP server

A Java MCP (Model Context Protocol) server exposing clients for multiple
bibliographic APIs (Entrez/PubMed, CrossRef, Europe PMC, Unpaywall,
OpenCitations, PMC IDConverter, Cermine PDF parsing) behind a unified record
format, plus a plain-Java facade over the same functionality.

Versions below are feature-release markers, not Maven artefact versions
(the Maven version is currently `master-SNAPSHOT`). Ideas under consideration
are recorded in [roadmap.md](/design/roadmap.md) before they become features.

<!-- @required: An example description of the overall project -->

## v0.1.0 - MCP server baseline

The R interface was replaced by an MCP server. Historical context:
[ADR-001](/architecture/decision-records/adr-001-replace-r-interface-with-mcp-server.md).

* [HAS_FEATURE](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md) - query several bibliographic APIs through one unified record shape
* [HAS_FEATURE](/design/features/feat-002-explore-citation-networks-in-a-session.md) - collect, merge and expand a citation network across tool calls
* [HAS_FEATURE](/design/features/feat-003-analyse-citation-graphs-and-topics.md) - page rank and topic modelling over a session

## v0.2.0 - implemented (unreleased)

* [HAS_FEATURE](/design/features/feat-006-classify-records-by-type-with-provenance.md) - publication type, publisher and provenance on records
* [HAS_FEATURE](/design/features/feat-007-predictable-record-merging.md) - one predictable source of truth when several APIs describe the same work

## Roadmap

Under consideration, not yet accepted:

* [HAS_FEATURE](/design/features/feat-004-opencitations-meta-enrichment.md) - OpenCitations Meta as an id-mapping source, rescuing non-DOI citation links
* [HAS_FEATURE](/design/features/feat-005-zotero-curated-repository-bridge.md) - read, publish into and maintain a curated Zotero library

<!-- @required: everything described in the ./features directory is linked to here -->
<!-- @required: target of links to features must exist -->
