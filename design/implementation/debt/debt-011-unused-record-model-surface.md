---
title: Unused record model surface
status: open
sinceVersion: 0.1.0
targetVersion: 0.4.0
tags:
- dead-code
- model
IMPACTS:
- main:java/uk/co/terminological/bibliography/record/CitationReference.java
- main:java/uk/co/terminological/bibliography/record/IdType.java
- main:java/uk/co/terminological/bibliography/record/PrintRecordWithCitations.java
---

## Debt desc

Three pieces of the record model are declared but never reached. Found while
writing the package documentation for `..bibliography.record`; none of it is
harmful, but each one costs a reader time working out what it is for and
finding that the answer is "nothing yet".

**`PrintRecordWithCitations`** — no implementation and no consumer anywhere in
`src/main` or `src/test`. The combination it describes (a record with print
coordinates *and* its citation list) is coherent, and `Record` /
`RecordWithCitations` / `PrintRecord` are all live, so this is a gap in the
lattice rather than a mistake. Keep if a source is expected to supply all three
in one payload; otherwise delete, since the Immutables processor generates a
full `ImmutablePrintRecordWithCitations` for it either way.

**`CitationReference.getBibliographicId()`** — written but never read. The only
writer is `Builder.citationReference(RecordReference)`, which populates it when
the argument happens to be a `PrintReference`; nothing calls the getter. This
one is a real functional gap rather than dead weight: the print coordinates are
the only usable description of a reference-list entry that carries no
identifier, and `CiteProcProvider` would need them to render such an entry. See
also issue-001, which records the related loss of non-DOI citation links.

**`IdType.MID` and `IdType.OPENALEX`** — never constructed. `OPENALEX` is the
more interesting absence: OpenCitations returns `openalex:` handles and
`OpenCitationsCitation.pidFromPids` deliberately keeps them, but as
`IdType.UNK`, so the enum value that exists to name them goes unused while the
identifiers it would name are labelled unknown. `MID` has no apparent source at
all.


Decide each independently rather than sweeping them together:

- `PrintRecordWithCitations`: delete unless a preprint/JATS path is expected to
  populate it.
- `getBibliographicId()`: wire it into citation rendering, or drop the field and
  the branch in `Builder` that populates it \u2014 writing a field nobody reads is
  the worst of the three options.
- `IdType.OPENALEX`: either use it in `pidFromPids` in place of `UNK` for
  `openalex:` handles, or remove it. `MID`: remove unless a source is known.

