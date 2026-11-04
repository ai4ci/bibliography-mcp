---
title: Fetch is incomplete by id type
status: closed
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- api-client
- id-types
IMPACTS:
- main:java/uk/co/terminological/bibliography/api/crossref/CrossRefClient.java
- main:java/uk/co/terminological/bibliography/api/BiblioFacade.java
---
## Debt desc

Two clients are keyed by DOI, so a fetch by another identifier type returns
nothing and the merged record is quietly thinner than it should be:

| client | by DOI | by PMID | why |
|---|---|---|---|
| CrossRef | 1 | **0** | `fetch` only accepts DOI refs, and CrossRef has **no `pmid` filter** — `filter=pmid:…` is rejected with `filter-not-available` (checked live 2026-09), so a PMID must be resolved to a DOI before the call |
| Europe PMC | 1 | 1 | handles DOI, PMID and PMCID natively (`getById`) |

(An earlier version of this record claimed Europe PMC missed DOIs. That was
wrong: it was measuring a cached **truncated response body**, not a code gap — see
[issue-004](/design/implementation/issues/issue-004-truncated-responses-are-cached-as-empty.md).
Europe PMC handles DOI, PMID and PMCID correctly.)

Consequence: an agent that only has a PMID gets Europe PMC's record but not
CrossRef's publisher/type or OpenAlex's open-access location, and nothing in the
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


## Closed — 0.2.0

A fourth option, cheaper than the three above, and the record's table had gone
stale in a way that mattered.

**What is actually missing, measured 2026-11-04 against a cold cache.** Only
CrossRef. A fetch by DOI reaches `[crossref, entrez, europepmc, openalex]`; the
same work by PMID reaches `[entrez, europepmc, openalex]`. OpenAlex became a
first-class source after this record was written and its `fetch` accepts `doi`,
`pmid`, `pmcid` and OpenAlex ids, so "not OpenAlex's open-access location" is no
longer true. medRxiv is DOI-keyed by nature — a preprint has no PMID — which is
a fact about preprints rather than a gap.

**Option D: ask again with what the first pass learned.** The sources that
*can* answer to a PMID hand back the DOI among the record's other identifiers,
so by the time the first pass is done the facade is already holding what the
silent source needed. `BiblioFacade.secondAsk` works out which sources said
nothing about which asked-for reference, and asks only those, only with a DOI
that was learned rather than given, carrying the answer back under the
identifier the caller asked about so issue-024's contract survives the detour.
Both `fetch` and `fetchBySource` use it: a source missing from an unmerged
answer is no less missing than from a merged one.

Against the options as written:

- **A** was the right shape and the wrong cost: an id-mapping round trip on
  every fetch, to learn something three of the five sources had just told us.
  Its stated blocker is also gone — `fetchFrom` has been a package-visible seam
  since issue-024, so the re-keying is asserted offline in
  `TestFetchSecondAsk` with sources that answer from a script.
- **B** would have put the same resolution in two clients.
- **C**, the recommendation, asks the agent to remember. Nothing now has to.

Costs nothing when there is nothing to learn: a caller who asked by DOI, or a
work whose DOI no source knew, makes no second call at all. That is asserted
rather than hoped for.

Live, cold cache: by PMID now reaches `[entrez, europepmc, openalex, crossref]`.
