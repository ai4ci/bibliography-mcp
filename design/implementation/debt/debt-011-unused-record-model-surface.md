---
title: Unused record model surface
status: closed
sinceVersion: 0.1.0
targetVersion: 0.4.0
tags:
- dead-code
- model
IMPACTS:
- main:java/uk/co/terminological/bibliography/record/CitationReference.java
- main:java/uk/co/terminological/bibliography/record/IdType.java
- main:java/uk/co/terminological/bibliography/CiteProcProvider.java
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


## Partly overtaken — 0.1.0

`IdType.OPENALEX` is now constructed: `OpenAlexClient.workReference` builds
references with it and `OpenAlexRecord` reports it among a work's identifiers, so
it is no longer a value that exists for nothing.

The specific complaint in this record still stands, though, which is why it stays
open: `OpenCitationsCitation.pidFromPids` continues to label an `openalex:` handle
as `UNK` while the enum value that names it is in use elsewhere. One client
constructing it does not make the other client's `UNK` right.

`PrintRecordWithCitations`, `getBibliographicId()` and `IdType.MID` are untouched.

## A third identifier question — 2026-11-03

OpenCitations returns `arxiv:` handles for cited entities that have no DOI, and
this project has no `IdType` for them, so they are kept whole as `UNK`
(see [issue-001](../issues/issue-001-non-doi-citation-links-dropped.md)). Measured,
that is about 1 in 120 cited entities — rare, but it is the *only* kind of
citation endpoint this project now cannot name.

It belongs with this record because it is the same question from the other side:
`IdType` carries a value nothing constructs (`MID`) and lacks one several
sources actually emit. Deciding either is deciding what the enum is for.

## Closed — 0.2.0 — each half decided independently

**Identifiers: the enum now has a principle.** "These are the schemes sources
actually emit" — nothing speculative. Three decisions, all measured rather than
assumed:

- **`IdType.MID` is deleted.** Constructed by nothing. The comment in
  `CrossRefReference` that hedged its meaning — "MID is a manuscript identifier,
  not a something-else bucket" — already answers the only question anyone asked
  of it: a reference with no identifier says `UNK`, like every other scheme-less
  case.
- **`arxiv:` handles are typed.** `IdType.ARXIV` joins `KNOWN_SCHEMES`, below
  ISBN because OpenCitations pairs it with a DOI essentially always — it is
  reached only when nothing better is in the PID list, which is exactly the
  1-in-120 the record measured. `Provenance.ARXIV` already named the source, so
  the enum and the provenance now agree.
- **The OpenAlex half of the record was already overtaken** and is now fully
  so: `KNOWN_SCHEMES` carries `openalex:`, and the tests assert it.

`TestIdTypeMembership` states the membership and the reason each member earns
its place, so the next value anyone adds has to name the source that emits it.

**Model surface: delete one, wire the other, as chosen.**

- **`PrintRecordWithCitations` is deleted.** No implementation, no consumer,
  and the combination it describes — pagination plus citation list in one
  payload — is not one any configured source returns. The lattice gap is gone
  rather than documented.
- **`CitationReference.getBibliographicId()` is wired in, not deleted.** The
  record called a written-but-unread field the worst of the options, and the
  unread-ness was the real gap: the print coordinates are the only usable
  description of an unidentified reference-list entry, roughly half of what the
  citation APIs return, and `CiteProcProvider` could not render one without
  them. `fromCitationReference` maps the coordinates into CSL, and `render`
  gives entries that are not `Record`s the same exception-free-but-logged
  contract `Record.render` has.

`TestUnidentifiedReferenceRendering` pins it: an entry that is nothing but
"Smith, Lancet 377(9775):1234-42, 2011" renders as a citation naming all four.
