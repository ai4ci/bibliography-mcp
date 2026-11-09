---
class: issue
id: issue-026-europepmc-citation-links-carry-the-wrong-index.md
title: EuropePMC citation links carry the wrong index
status: closed
sinceVersion: 0.1.0
targetVersion: 0.2.0
TESTED_BY:
- test:java/uk/co/terminological/bibliography/api/europepmc/TestEuropePMCCitationIndex.java
---
`CitationLink.getIndex()` is documented as the "position in the citing work's
reference list, where the source supplied one", and "only meaningful for links
obtained by reading a work's own references — a 'cited by' answer has no list to
be a position in". `EuropePMCClient` honours neither half.

**Every link gets index 0.** The counter is declared and never incremented, so a
work's entire reference list is reported as position zero. This was the same
one-character omission repeated in each of the six loops that
[debt-010](/design/implementation/debt/debt-010-europepmc-duplicates-its-citation-loops.md)
removed; there is now one copy of it, in `citesReferences`.

**A cited-by link carries an index at all.** `referencesCiting` passes the same
counter, so every "cited by" link claims position zero in a reference list it
was never read from. The contract says this should be absent, and the other
clients agree: `EntrezLinks` passes `null` in both directions and
`OpenAlexClient.referencesCiting` passes `null`.

Found while closing debt-010 and deliberately **not** fixed there, because the
refactor was meant to be behaviour-preserving and this is pre-existing. Both
call sites carry a comment pointing here.

## Suggested fix

In `citesReferences`, start the counter at 1 and increment it inside the loop,
matching `OpenAlexRecord.getCitations` and `OpenAlexClient.citesReferences`. In
`referencesCiting`, pass `null` instead of the counter and delete it.

Worth checking before changing: `CitationLink` is an Immutables value type, so
the index is part of its `equals`. Both methods collect into a `HashSet`, and
making the index vary could change what is considered a duplicate — in practice
the source and target already differ per link, so the set size should not move,
but the live citation counts are the thing to compare before and after.

## Resolution — 0.2.0

Fixed as suggested, with the numbering lifted out of the API loops into two
package-private helpers on `EuropePMCClient` so it can be tested without a call:

- `referenceList(citing, cited)` numbers from 1 and increments per reference,
  matching `OpenAlexClient.citesReferences`.
- `citedByList(cited, citing)` passes `null`, matching `EntrezLinks` and
  `OpenAlexClient.referencesCiting`. The direction is unchanged — the work asked
  about is the target (issue-007).

`citesReferences` and `referencesCiting` now each fetch and delegate, and the
comments pointing here are gone.

The equality caution above turned out to matter, in the opposite direction to
the worry: because `CitationLink`'s index is part of its `equals` and these are
collected into a `HashSet`, a reference list numbered entirely zero **collapsed
duplicate-looking links**, and varying the index can only increase the set size,
never decrease it. `everyReferenceKeepsItsOwnPlace` in the test below pins that.

Proved by `TestEuropePMCCitationIndex` (4 tests, offline): a reference list is
numbered 1..n, three positions are three distinct links, a "cited by" link has no
index, and a "cited by" link runs from the citing work. The whole offline suite
is 344 tests, 0 failures.

The live counts were not compared before and after, because the live tier is
opt-in and rate-limit-sensitive; the set-size reasoning above says a count can
only rise, and `TestEuropePMCClientEndToEnd.theSamePaperGivesTheSameReferencesByEveryIdentifier`
compares the three identifier routes to each other whenever it is run.
