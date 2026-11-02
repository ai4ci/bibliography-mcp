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
- [Clean up collection metadata](features/feat-008-clean-up-collection-metadata.md)
- [Preprint clients](features/feat-009-preprint-clients.md)
- [Crawl engine with Zotero mirror](features/feat-010-crawl-engine-with-zotero-mirror.md)
<!-- loom:links end -->

## Work in progress

<!-- loom view: work_in_progress -->

| uri | title | status | targetVersion |
| --- | --- | --- | --- |
| [Non doi citation links dropped](implementation/issues/issue-001-non-doi-citation-links-dropped.md) | Non doi citation links dropped | open | 0.2.0 |
| [Third party api endpoint drift](implementation/issues/issue-002-third-party-api-endpoint-drift.md) | Third party api endpoint drift | open | 0.2.0 |
| [One failing api empties a multi api search](implementation/issues/issue-013-one-failing-api-empties-a-multi-api-search.md) | One failing api empties a multi api search | open | 0.3.0 |
| [Entrez record dates are not publication dates](implementation/issues/issue-014-entrez-record-dates-are-not-publication-dates.md) | Entrez record dates are not publication dates | open | 0.3.0 |
| [Search tool offers an api it cannot dispatch](implementation/issues/issue-020-search-tool-offers-an-api-it-cannot-dispatch.md) | Search tool offers an api it cannot dispatch | open | unassigned |
| [Secrets are loaded into system properties](implementation/issues/issue-021-secrets-are-loaded-into-system-properties.md) | Secrets are loaded into system properties | open | unassigned |
| [Shaded jar cannot render citations](implementation/issues/issue-022-shaded-jar-cannot-render-citations.md) | Shaded jar cannot render citations | open | unassigned |
<!-- loom view: work_in_progress end -->

## Open debt

<!-- loom view: open_debt -->

| uri | title | targetVersion |
| --- | --- | --- |
| [Opencitations queries dois only](implementation/debt/debt-002-opencitations-queries-dois-only.md) | Opencitations queries dois only | 0.2.0 |
| [Session persistence uses java serialization](implementation/debt/debt-001-session-persistence-uses-java-serialization.md) | Session persistence uses java serialization | 0.3.0 |
| [Analysis recomputes topic model per call](implementation/debt/debt-003-analysis-recomputes-topic-model-per-call.md) | Analysis recomputes topic model per call | 0.3.0 |
| [Fetch is incomplete by id type](implementation/debt/debt-005-fetch-is-incomplete-by-id-type.md) | Fetch is incomplete by id type | 0.3.0 |
| [Cached answers are never invalidated](implementation/debt/debt-006-cached-answers-are-never-invalidated.md) | Cached answers are never invalidated | 0.3.0 |
| [Cache stores break on cached class changes](implementation/debt/debt-007-cache-stores-break-on-cached-class-changes.md) | Cache stores break on cached class changes | 0.3.0 |
| [Javadoc build cannot see generated sources](implementation/debt/debt-012-javadoc-build-cannot-see-generated-sources.md) | Javadoc build cannot see generated sources | 0.3.0 |
| [Shared read timeout is a trap](implementation/debt/debt-009-shared-read-timeout-is-a-trap.md) | Shared read timeout is a trap | 0.4.0 |
| [Unused record model surface](implementation/debt/debt-011-unused-record-model-surface.md) | Unused record model surface | 0.4.0 |
| [Europepmc duplicates its citation loops](implementation/debt/debt-010-europepmc-duplicates-its-citation-loops.md) | Europepmc duplicates its citation loops | 0.5.0 |
<!-- loom view: open_debt end -->


<!-- loom view: features_by_state -->

| status | features |
| --- | --- |
| draft | 3 |
| final | 8 |
<!-- loom view: features_by_state end -->
