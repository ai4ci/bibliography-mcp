---
title: Design index
class: index
view:
  class: feature
  orderBy: targetVersion
targetVersion: unassigned
status: draft
---

# Design index

Generated navigation. The feature list between the `loom:links` markers is
written by `loom index`; the tables between the `loom view` markers are written
by `loom render` from the `%% @view` queries in `document-model.mmd`. Both are
checked in CI with `--check`, so a table that no longer matches the documents
fails the build instead of quietly lying.

## Features

<!-- loom:links start -->
- [Expose bibliographic apis over mcp](features/feat-001-expose-bibliographic-apis-over-mcp.md)
- [Explore citation networks in a session](features/feat-002-explore-citation-networks-in-a-session.md)
- [Analyse citation graphs and topics](features/feat-003-analyse-citation-graphs-and-topics.md)
- [OpenAlex as a provider](features/feat-011-openalex-as-a-provider.md)
- [Opencitations meta enrichment](features/feat-004-opencitations-meta-enrichment.md)
- [Zotero curated repository bridge](features/feat-005-zotero-curated-repository-bridge.md)
- [Classify records by type with provenance](features/feat-006-classify-records-by-type-with-provenance.md)
- [Predictable record merging](features/feat-007-predictable-record-merging.md)
- [Crawl engine with Zotero mirror](features/feat-010-crawl-engine-with-zotero-mirror.md)
- [Clean up collection metadata](features/feat-008-clean-up-collection-metadata.md)
- [Preprint clients](features/feat-009-preprint-clients.md)
<!-- loom:links end -->

## Work in progress

<!-- loom view: work_in_progress -->

| uri | title | status | targetVersion |
| --- | --- | --- | --- |
| [Third party api endpoint drift](implementation/issues/issue-002-third-party-api-endpoint-drift.md) | Third party api endpoint drift | open | 0.2.0 |
| [EuropePMC citation links carry the wrong index](implementation/issues/issue-026-europepmc-citation-links-carry-the-wrong-index.md) | EuropePMC citation links carry the wrong index | open | 0.3.0 |
| [Secrets are loaded into system properties](implementation/issues/issue-021-secrets-are-loaded-into-system-properties.md) | Secrets are loaded into system properties | open | unassigned |
| [PDF merge priority names a source that never answers](implementation/issues/issue-027-pdf-merge-priority-names-a-source-that-never-answers.md) | PDF merge priority names a source that never answers | open | unassigned |
| [PdfFetcher singleton ignores a second cache path](implementation/issues/issue-028-pdffetcher-singleton-ignores-a-second-cache-path.md) | PdfFetcher singleton ignores a second cache path | open | unassigned |
| [Downloaded PDFs share a cache sized by entry count](implementation/issues/issue-029-downloaded-pdfs-share-a-cache-sized-by-entry-count.md) | Downloaded PDFs share a cache sized by entry count | open | unassigned |
<!-- loom view: work_in_progress end -->

## Open debt

<!-- loom view: open_debt -->

_(no results)_
<!-- loom view: open_debt end -->


<!-- loom view: features_by_state -->

| status | features |
| --- | --- |
| draft | 2 |
| final | 9 |
<!-- loom view: features_by_state end -->
