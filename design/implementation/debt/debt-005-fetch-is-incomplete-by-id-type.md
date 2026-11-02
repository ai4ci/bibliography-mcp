---
title: Fetch is incomplete by id type
status: open
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- api-client
- id-types
IMPACTS:
- main:java/uk/co/terminological/bibliography/api/crossref/CrossRefClient.java
- main:java/uk/co/terminological/bibliography/api/BiblioFacade.java
- main:java/uk/co/terminological/bibliography/api/unpaywall/UnpaywallClient.java
---

## Debt desc

Two clients are keyed by DOI, so a fetch by another identifier type returns
nothing and the merged record is quietly thinner than it should be:

| client | by DOI | by PMID | why |
|---|---|---|---|
| CrossRef | 1 | **0** | `fetch` only accepts DOI refs, and CrossRef has **no `pmid` filter** — `filter=pmid:…` is rejected with `filter-not-available` (checked live 2026-09), so a PMID must be resolved to a DOI before the call |
| Europe PMC | 1 | 1 | handles DOI, PMID and PMCID natively (`getById`) |
| Unpaywall | 1 | **0** | DOI-keyed API by design (`/v2/{doi}`) |

(An earlier version of this record claimed Europe PMC missed DOIs. That was
wrong: it was measuring a cached **truncated response body**, not a code gap — see
[issue-004](/design/implementation/issues/issue-004-truncated-responses-are-cached-as-empty.md).
Europe PMC handles DOI, PMID and PMCID correctly.)

Consequence: an agent that only has a PMID gets Europe PMC's record but not
CrossRef's publisher/type or Unpaywall's open-access location, and nothing in the
response says a source was skipped.


| # | Option | Effort | Risk |
|---|--------|--------|------|
| A | Resolve identifiers to DOIs in the fetch path: `BiblioFacade.fetch` calls the existing bulk `IdMapper` for non-DOI refs, queries DOI-only clients with the resolved DOI, and keys the results back to the requested reference so merging still works | M | Extra mapping round trip (bulk, so cheap per item); needs a seam to unit-test the re-keying offline; mapping coverage is partial (PMC-only for the ID converter) |
| B | Give each DOI-only client its own resolver | M | Duplicated resolution logic in two clients |
| C | Document the workflow instead: `map_ids` then `fetch` (or fetch via Europe PMC, which covers all three id types) | S | None — but it is the agent's job to remember |

**Recommendation:** C now (documented in the skill), A only if it proves annoying
in practice. Recorded rather than fixed because an open issue is expected to carry
a reproducing test and there is no offline seam today — the same reason the
`getById` behaviour cannot be asserted without network.

