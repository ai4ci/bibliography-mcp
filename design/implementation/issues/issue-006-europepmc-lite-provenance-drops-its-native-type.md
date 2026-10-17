---
status: closed
since-version: 0.2.0
target-version: 0.2.0
tags: model, provenance, europepmc, refactor-regression
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Issue desc

`EuropePMCLiteResult.getProvenance()` returns `Provenance.placeholder(EUROPEPMC)`,
so the record reports its source but not the vocabulary Europe PMC used to
describe it. The native type is the whole point of carrying provenance on a
record ([feat-006](/design/features/feat-006-classify-records-by-type-with-provenance.md)):
the CSL type is a lossy mapping, and the native string is what lets a
disagreement be attributed or an unmapped type be recovered.

The data is already in hand — the same class exposes `getNativePubTypes()`
immediately above, and `getPublicationType()` consumes it — so this is a
dropped wire-up, not missing data.

Introduced by the "Major refactor and clean up" commit. Before it the method
read:

    return Optional.of(Provenance.of(Provenance.EUROPEPMC, getNativePubTypes().orElse(null)));

`Provenance.of` has since become null-intolerant, which is probably why the
call was replaced with `placeholder` rather than adapted.

`EuropePMCCoreResult` is **not** affected — it builds
`Provenance.canonical(EUROPEPMC, getNativePubTypes())`, which takes the native
type as an `Optional` and so survived the refactor. Only the lite (search
result) shape lost it. Note that its test asserts only the source, so it would
not have caught the regression either.

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/record/TestRecordModel.java)

`europePmcLitePubTypeStringMapsToCsl` asserts the native pubType string survives
on provenance.

## Resolution

Fixed in [EuropePMCLiteResult](/src/main/java/uk/co/terminological/bibliography/api/europepmc/EuropePMCLiteResult.java):
the provenance is built from the native pub types, falling back to the
placeholder only when the response carries none.

    return this.getNativePubTypes()
        .map(t -> Provenance.of(Provenance.EUROPEPMC, t))
        .orElseGet(() -> Provenance.placeholder(Provenance.EUROPEPMC));

This keeps the lite result non-canonical, which is the distinction `placeholder`
was reaching for: a search summary is not the full record, so
`Provenance.canonical` would be wrong here even though it accepts the optional
directly.

Still outstanding, and deliberately not bundled in: `europePmcCorePubTypeListMapsToCsl`
asserts only the source, so the (correct) core path has no coverage of its
native type.
