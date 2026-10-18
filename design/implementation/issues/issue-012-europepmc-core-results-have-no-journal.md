---
status: closed
since-version: 0.1.0
target-version: 0.3.0
tags: europepmc, model, parsing
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Resolution

Fixed 2026-10-17 (developer): `EuropePMCCoreResult` overrides `getJournal()` to
read `journalInfo/journal/title`. Verified live -- every record from a search
now reports its journal, where before all 25 of 25 reported none.

Still worth checking, deliberately not bundled: `getVolume()` and `getIssue()`
have the same flat-versus-nested split (`journalVolume`/`issue` on a lite
payload, `journalInfo.volume`/`journalInfo.issue` on a core one) and no test
pins them against the recorded core fixture.

## Issue desc

Every record returned by `EuropePMCClient.search()` reports no journal.
Confirmed live: 25 of 25 results had an empty `getJournal()`.

`EuropePMCCoreResult` inherits `getJournal()` from `EuropePMCLiteResult`, which
reads a top-level `journalTitle`:

    public Optional<String> getJournal() { return this.asString("journalTitle"); }

That field exists on a **lite** response. A **core** response nests it instead:

    journalInfo.journal.title            e.g. "BMC infectious diseases"
    journalInfo.journal.medlineAbbreviation  e.g. "BMC Infect Dis"

`search()` and `execute()` request `resultType=core`, so the shape that is
actually used in the search path is the one that is not parsed. The same
applies to `getVolume()` (`journalVolume` → `journalInfo.volume`) and
`getIssue()` (`issue` → `journalInfo.issue`), which are worth checking at the
same time.

The gap was known at the raw-payload level — the existing test
`coreResultsCarryTheMedlineAbbreviationFromJournalInfo` reaches into the raw
node with the comment "no getter exists for it" — but the consequence through
the `Record` interface was not: the container title is simply missing from
every Europe PMC search result.

## Why it matters

The container title is not decoration. It feeds the `CONTAINER` entry in
`MergePolicy`, where Europe PMC is ranked second; the Zotero cleanup report,
which compares an item's `publicationTitle` against the APIs and will report a
spurious gap or conflict; and `zotero_publish`, which writes the field. A
record with no journal silently loses to any source that has one, and a Europe
PMC-only record is published to Zotero with the field blank.

It also made a query-translation test inconclusive rather than failing
honestly: `europePmcRestrictsToTheJournalAsked` cannot judge whether the
journal filter works while no result reports a journal.

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/api/europepmc/TestEuropePMCExtraction.java)
[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java)

`coreResultsReportTheirJournalThroughTheRecordInterface` offline, against the
recorded `fixtures/europepmc/search-med.json`.

## Suggested fix

Override the container accessors in `EuropePMCCoreResult` to read the nested
`journalInfo` path, falling back to the flat field so a lite payload parsed
through the same class still works:

    this.asString("journalTitle")
        .or(() -> raw path journalInfo/journal/title)

Check `getVolume()`, `getIssue()` and the publication date for the same
flat-versus-nested split while there, and extend the extraction test to pin
each one against the recorded core fixture.
