---
title: Opencitations queries dois only
status: open
sinceVersion: 0.1.0
targetVersion: 0.2.0
tags:
- opencitations
- id-types
IMPACTS:
- main:java/uk/co/terminological/bibliography/api/BiblioFacade.java
- main:java/uk/co/terminological/bibliography/api/opencitations/OpenCitationsClient.java
---

`OpenCitationsClient.citesReferences` / `referencesCiting` filter the input
references to `IdType.DOI` before querying, because the links are built from
single DOI identifiers. OpenCitations Index v2 accepts `pmid:` and `omid:`
identifiers for the same operations, so records known only by PMID are
silently skipped — an agent querying by PMID gets zero citations from this API
with no indication why.

Two ways out: widen the query filter and carry the identifier type through
`CitationLink` (the internal model stores one `IdType` + value per reference),
or resolve non-DOI references to DOIs first via id mapping
([feat-004](/design/features/feat-004-opencitations-meta-enrichment.md)).

Related: [issue-001](/design/implementation/issues/issue-001-non-doi-citation-links-dropped.md)
is the mirror-image problem on the response side.

