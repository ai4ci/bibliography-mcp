---
title: Opencitations meta enrichment
status: draft
targetVersion: 0.2.0
tags:
- opencitations
- id-mapping
- proposed
HAS_PLAN:
- plan:plan-001-opencitations-meta-idmap.md
HAS_PROTOTYPE:
- prototype:prototype-001-opencitations-meta-pids.md
---

## Description

Citation links whose endpoints carry no DOI are currently discarded, because
citation links are constructed from a single DOI identifier and incomplete
links are dropped by the session. OpenCitations Meta exposes a complete
identifier equivalence class per entity (doi, pmid, pmcid, openalex, omid,
isbn, issn), which can act as an id-mapping source broader than the PMC
IDConverter and so rescue those links.

This is **a proposal, not yet accepted** — options and trade-offs are
recorded in [roadmap idea 1](/design/roadmap/index.md). Implementing it should
wait for sign-off on the plan.


- [issue-001](/design/implementation/issues/issue-001-non-doi-citation-links-dropped.md)
- [debt-002](/design/implementation/debt/debt-002-opencitations-queries-dois-only.md)
