---
status: open
since-version: 0.1.0
target-version: 0.2.0
tags: opencitations, id-types
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: since-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Debt desc

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

## Impacts

- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/opencitations/OpenCitationsClient.java)
- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/mcp/BiblioFacade.java)
