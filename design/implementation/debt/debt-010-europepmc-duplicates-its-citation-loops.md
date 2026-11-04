---
title: Europepmc duplicates its citation loops
status: closed
sinceVersion: 0.1.0
targetVersion: 0.5.0
tags:
- duplication
- europepmc
IMPACTS:
- main:java/uk/co/terminological/bibliography/api/europepmc/EuropePMCClient.java
---
`EuropePMCClient.citesReferences` and `referencesCiting` each contain near-identical
loops, one per identifier type, and there are now six of them. That duplication is not
cosmetic: it is why a `DOI` branch was missing from both for so long — the same gap had
to be noticed and fixed twice, and it was not noticed at all until a preprint's
references came back empty while its API held 31.

The index counter was also declared-and-never-incremented in each copy, so the same
one-character bug existed in four places.

Cheapest useful step: one private method per direction, parameterised by the resolved
source and id, so an identifier type is added in one place. Worth doing the next time
either method is touched rather than as a dedicated refactor.


## Closed — 0.2.0

Six loops become one `epmcTarget`, which is the record's own cheapest useful
step, taken while the methods were open rather than as a dedicated refactor.

The identifier handling is now a single method that answers "Europe PMC's own
source and id for this reference, or nothing". `directSource` states the two
that are true by construction — a PMID is a Medline record, a PMCID a full-text
one — and a DOI is the only type that costs a search to place, because Europe
PMC indexes preprints as `PPR` and journal articles as `MED`. Adding an
identifier type is one edit, in one place, and
`TestEuropePMCIdentifiers` fails if it is not made.

Two things fall out of the collapse:

- The loops called `getIdentifier().get()` unguarded. A reference carrying a
  type and a blank identifier would have been sent as a URL path segment and
  answered with a 404, which reads as "no citations". It is now skipped.
- `referencesForDoi` and `citationsForDoi` existed only to hold the DOI branch
  and are gone; `resolveDoi` and the public `resolveDoiSource` stay.

The index counter this record also names is **not** fixed here. The refactor is
behaviour-preserving by design, and the counter is a pre-existing defect with a
second half the record does not mention — a "cited by" link should carry no
index at all, and this client gives it zero. Both are written up with a
suggested fix and a caution about `CitationLink` equality in
[issue-026](/design/implementation/issues/issue-026-europepmc-citation-links-carry-the-wrong-index.md),
and both call sites point at it.
