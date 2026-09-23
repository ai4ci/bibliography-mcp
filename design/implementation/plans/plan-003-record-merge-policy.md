---
status: final
target-version: 0.2.0
tags: model, merge
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

<!-- acts as a description of what will be implemented to allow review and sign-off -->

## Feature

[HAS_FEATURE](/design/features/feat-007-predictable-record-merging.md)

Terse implementation checklist. Depends on
[feat-006](/design/features/feat-006-classify-records-by-type-with-provenance.md)
(provenance) landing first — the policy reads `Provenance.source`.

## Policy to implement

1. **Grouping unchanged**: records sharing any identifier are one work
   (`Session.merge` already groups by `ImmutableRecordReference`).
2. **Exclude curated records**: a record whose provenance marks it curated
   (Zotero) is *not* merged into the API-derived record. Both remain attached to
   the same work node (the graph node is identifier-based and shared), and are
   distinguishable by provenance.
3. **Per-field resolution by documented priority**, replacing `findAny()`:
   highest-priority non-empty value wins, where priority is a fixed table, e.g.

   | field | priority order (proposal) |
   |---|---|
   | title | crossref, europepmc, entrez |
   | abstract | europepmc, entrez, crossref |
   | journal / container | crossref, europepmc, entrez |
   | date | crossref, entrez, europepmc |
   | pdf / OA location | unpaywall, europepmc, crossref |
   | publication type | crossref, europepmc, zotero |
   | publisher | crossref, zotero |
   | *fallback* | a single global order for anything not listed |

   No length/heuristic comparisons — those are silently unpredictable in spirit
   even when deterministic in letter.
4. **Unions**: identifiers (all, distinct); authors (de-duplicate by ORCID when
   present, else normalised surname+initials, preserving the order of the
   highest-priority source); licences (distinct); citations (union).
5. **Retain contributions**: keep every contributing `ImmutableRecord` (already
   the case) so a loser is recoverable and reportable.
6. **Determinism guarantee**: resolution must not depend on insertion order or
   stream order — that is the acceptance test.

## Implementation steps:

- [x] Introduce a `MergePolicy` (name TBD) holding the per-field priority table
      and the curated-source predicate. Keep it a plain object with sensible
      defaults; no config file needed yet.
- [x] Rewrite the `MergedRecord` accessors to resolve through the policy
      (ordered reduction over contributing records) instead of `findAny()`.
- [x] De-duplicate authors (ORCID, else normalised name); keep source order.
- [x] Exclude curated-provenance records from *being overridden*: a curated value is
      authoritative and API values only fill the gaps it leaves (see the note below).
- [ ] (not done) Keep a curated record as its own work entry — needs the session
      id-map change, see the note below. Original step: exclude curated-provenance records from the merge in `Session.add` /
      `MergedRecord.merge`, whichever is the single choke point — verify which,
      and keep it to one place.
- [x] Removed the two stale `//TODO: Check for duplicates` / `//TODO: resolve
      duplicates` comments once they are true.
- [x] Expose contributing provenances (from feat-006) so a caller can see who
      said what.
- [ ] Optional: a `session_conflicts`-style report listing works whose
      contributing records disagree, since nothing is silently discarded.

## Tests (offline)

- [x] Rewrite `TestRecordMerge`: same inputs in both insertion orders produce
      **identical** merged output (this is the failing-then-passing acceptance
      test for issue-003).
- [x] Authors from two sources appear once.
- [x] A curated (Zotero-provenance) value is never overridden by an API value.
- [x] Priority: with title present only in the lower-priority source, that value
      is still used (non-empty beats priority).

## Verification

- [x] `mvn -o test -Dtest=TestRecordMerge` — 12/12
- [x] `mvn -o test-compile`, `./scripts/mcp-smoke-test.sh`
- [x] `Rscript …/design-check.R -d .` → 0 defects

## Open questions

- Exact priority order per field (the table above is a proposal — CrossRef first
  for title/date, Europe PMC first for abstracts).
- Author de-duplication key when ORCID is absent.
- Whether licences are unioned or priority-picked.
- Whether a source may be excluded per field (e.g. never take a title from a
  repository-derived record).
