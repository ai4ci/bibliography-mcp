---
status: final
target-version: 0.2.0
tags: model, provenance, zotero
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

<!-- acts as a description of what will be implemented to allow review and sign-off -->

## Feature

[HAS_FEATURE](/design/features/feat-006-classify-records-by-type-with-provenance.md)

Implementation notes, deliberately terse — this is a working checklist for
whoever implements it. Decisions are locked in
[roadmap idea 3](/design/roadmap.md); do not re-litigate them mid-flight.

## Approach

**Everything lands as `default` methods on `Record`**, so the ~17 existing
implementations (`CrossRefWork`, `EntrezEntry`, `JatsEntry`, `MedrxivSearchEntry`,
`UnpaywallResult`, `EuropePMC*`, …) keep compiling untouched and each mapper opts
in. Only `ImmutableRecord` (field-by-field copy) and `MergedRecord` need edits,
plus the mappers we choose to fill in.

## Implementation steps:

- [x] Add `ISBN` to `IdType`. Note: there is no `switch` over `IdType` in the
      codebase, so this is safe; check the parsing of user-supplied type strings
      (`IdType.valueOf`) still behaves for unknown input.
- [x] Add `Provenance` to the `record` package: immutable value type
      `{source, nativeType, fetchedAt, url}` + builder/`Builder.provenance(...)`.
      `source` is the API name (`crossref`, `entrez`, `europepmc`, `zotero`);
      `nativeType` is the source's own vocabulary; a Zotero `itemKey` belongs
      here (linkage, partitioned by `Zotero-Server-ID`), **not** in `IdType`.
- [x] Add `default` methods to `Record`: `getPublicationType()`,
      `getPublisher()`, `getProvenance()` (all `Optional.empty()`), and
      `getContainerTitle()` defaulting to `getJournal()`.
- [x] `ImmutableRecord`: copy the new values in the constructor and expose them
      (it copies field-by-field, so this is the one class that will silently drop
      them otherwise).
- [x] `MergedRecord`: expose `getProvenance()` / `getProvenances()` over the
      records it holds, so provenance survives merging — the whole point.
      Follow the existing `findAny()` style for the single-value accessors; no
      precedence policy is being introduced (duplicates are tolerated).
- [x] CrossRef: add `getPublicationType()` → `asString("type")` and
      `getPublisher()` → `asString("publisher")` on `CrossRefWork`, as dynamic
      accessors in the style of the existing `getJournal()`. The `publisher`
      field is declared-but-commented at `CrossRefWork.java:103`; prefer the
      accessor over resurrecting the field.
- [x] Europe PMC: `getPublicationType()` — both shapes handled (`pubType` string on lite, `pubTypeList.pubType` array on core; verified live)
      (`streamPath("pubTypeList","pubType")`, array of strings) mapped through a
      small PubMed-type → CSL table, recording the full native list in
      `Provenance.nativeType`. Minimum table: `Journal Article`→`article-journal`,
      `Review`/`Systematic Review`→`review`, `Preprint`→`posted-content` if the
      citeproc version supports it else `article`, default `article-journal`.
- [ ] Entrez: parse `PublicationTypeList` **only if something consumes it** —
      otherwise leave as a documented gap.
- [ ] Zotero adapter (when built): map `data.itemType` → CSL. **Generate the
      table by asking Zotero itself** — request `format=csljson` for one item of
      each native type and read the `type` Zotero emits, rather than hand-writing
      guesses. Publisher: `publisher` (books) and `institution` (reports).
      Date is free text (`"2013"`, `"2013-05"`, `"2026-09-16"`) — parse tolerantly.
- [x] **Surface them to agents**: `McpTools.recordJson` is the observable
      surface (currently 11 fields) — add `publicationType`, `publisher` and a
      compact provenance (`source`, maybe `nativeType`). Without this the whole
      change is invisible to MCP clients.
- [x] Update the docs that enumerate record fields —
      `skills/using-bibliographic-apis/references/mcp-call-sequences.md` lists
      the returned record shape, and `README.md` describes the tool surface.
- [x] Add design links in the changed source files
      (`// [IMPLEMENTS](/design/features/feat-006-classify-records-by-type-with-provenance.md)`).

## Tests (offline, no live APIs)

- [x] `TestRecordModel` (11 tests):
      CrossRef `type`/`publisher` accessors over a recorded payload; Europe PMC
      `pubTypeList` mapping; default `getContainerTitle()` falling back to
      `getJournal()`; unknown CSL type passes through unchanged.
- [x] Provenance survives: build a `MergedRecord` from two records with
      different sources and assert both provenances are retrievable.
- [x] MCP output: a `tools/call` result includes `publicationType`/`publisher`
      for a record that has them (extend the offline smoke assertions or unit-test
      `recordJson` directly).

## Verification

- [x] `mvn -o test-compile` (all ~17 implementations still compile with no edits)
- [x] `mvn -o test -Dtest=TestRecordModel` — 11/11
- [x] `./scripts/mcp-smoke-test.sh` unchanged
- [x] `Rscript …/design-check.R -d .` → 0 defects

## Pitfalls

- Adding the fields to `Record` as abstract (not default) methods would force
  edits in every implementation — that is the whole reason for the default-method
  approach.
- `ImmutableRecord` will drop new fields unless explicitly copied.
- Zotero's CSL export maps `preprint`→`article`, so the canonical type alone
  cannot distinguish preprints — `Provenance.nativeType` is not optional.
- Do not put `itemKey` in `IdType`: it is instance-scoped (see `Zotero-Server-ID`).
