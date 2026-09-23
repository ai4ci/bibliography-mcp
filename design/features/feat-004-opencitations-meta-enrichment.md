---
status: draft
target-version: 0.2.0
tags: opencitations, id-mapping, proposed
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Description

Citation links whose endpoints carry no DOI are currently discarded, because
citation links are constructed from a single DOI identifier and incomplete
links are dropped by the session. OpenCitations Meta exposes a complete
identifier equivalence class per entity (doi, pmid, pmcid, openalex, omid,
isbn, issn), which can act as an id-mapping source broader than the PMC
IDConverter and so rescue those links.

This is **a proposal, not yet accepted** — options and trade-offs are
recorded in [roadmap idea 1](/design/roadmap.md). Implementing it should
wait for sign-off on the plan.

## Linked artefacts

- [HAS_PROTOTYPE](/design/prototypes/prototype-001-opencitations-meta-pids.md)
- [HAS_PLAN](/design/implementation/plans/plan-001-opencitations-meta-idmap.md)

## Related

- [issue-001](/design/implementation/issues/issue-001-non-doi-citation-links-dropped.md)
- [debt-002](/design/implementation/debt/debt-002-opencitations-queries-dois-only.md)
