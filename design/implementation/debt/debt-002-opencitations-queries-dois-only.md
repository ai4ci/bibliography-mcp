---
title: Opencitations queries dois only
status: closed
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


## Closed — 0.2.0

The client asks about every identifier the API accepts, and says in the log what
it cannot ask about.

Which schemes those are was **measured rather than read**: `doi`, `pmid` and
`isbn` answer 200; `pmcid` and `openalex` answer **400**, so sending one spends
a call to be refused. `omid` works too, and nothing can ask about one because
this project has no id type for it.

The record offered two ways out and this takes the first — widen the filter —
because the second, resolving everything to a DOI first, costs an id-mapping
round trip to reach an API that was willing to answer the original question.

Live: the same paper asked by DOI and by PMID returns the same 17 references,
and a PMID-only record that used to get silence now gets its reference list.
What cannot be asked about is logged with the count and the types, which is the
"no indication why" half of this record.
