---
status: open
since-version: 0.1.0
target-version: 0.5.0
tags: duplication, europepmc
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: since-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Debt desc

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

## Impacts

- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/europepmc/EuropePMCClient.java)
