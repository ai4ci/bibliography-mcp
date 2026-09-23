---
status: open
since-version: 0.1.0
target-version: 0.3.0
tags: api-client, id-types
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: since-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Debt desc

Each client's `fetch` only understands some identifier types, so a fetch by the
"wrong" id silently returns nothing:

| client | by DOI | by PMID |
|---|---|---|
| CrossRef | 1 record | 0 records |
| Europe PMC | 0 records | 1 record |
| Unpaywall | 1 record | 0 records |

(measured live 2026-09). For the MCP `fetch` tool the consequence is that a DOI
request misses Europe PMC's abstracts, and a PMID request misses CrossRef's
publisher and type — so the merged record is quietly thinner than it should be
and nothing in the response says a source was skipped.

Recorded as debt rather than an issue because an open issue is expected to carry
a reproducing test and there is no offline seam: the filtering happens inline
around an HTTP call.

Fix path (both halves work at the API level):

- Europe PMC: teach `getById` to handle `IdType.DOI` with a `DOI:"10.x"` query —
  the search path already issues exactly that query successfully.
- CrossRef: accept PMID/PMCID via CrossRef's `filter=pmid:…` / `filter=pmcid:…`.
- Extract the id-to-query-parameter mapping into a small pure function so both
  directions can be unit-tested offline, then the debt can become an issue with a
  real reproduction.

## Impacts

- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/europepmc/EuropePMCClient.java)
- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/crossref/CrossRefClient.java)
- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/mcp/BiblioFacade.java)
