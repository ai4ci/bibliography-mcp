# Working notes — where things stand

Handover note, 2026-11-02. Not part of the graph (no frontmatter, no loom type);
delete or absorb it whenever the items below are dealt with.

Committed as `aaab0b1 Add OpenAlex as a provider and retire Unpaywall (feat-011,
plan-013)` on `main`, ahead of `origin/main` by 4 commits. Working tree clean.
`mvn -o clean test`: **190 tests, 0 failures**. `TestOpenAlexClientEndToEnd`
(live): **13 tests, 0 failures**. `loom validate`: 0 errors. `loom render --check`:
not stale. **`git push` is outstanding** — that is the developer's job.

## What just landed

`OpenAlexClient` implements all five SPI roles and is registered for cites,
citedBy, fetch and idMap. It is **not** in `searchApis` (10 credits a call against
~1,000 searches a day; three free search sources exist). Unpaywall is deleted,
issue-016 closed by retirement rather than by a fix. `MergePolicy` names `openalex`
where it named `unpaywall` — that test was written and proven to fail first, per
AGENTS.md.

Plan-013 now carries the real follow-ups under **"Status: done, with follow-ups"**;
read that rather than this file for detail on the client itself.

## Loose ends, in the order they are worth taking

1. **issue-017 — `GenericSearchBuilder.withQuery()` is a no-op** (returns `this`
   without assigning `qry`), so `execute()` searches with a null query. Small,
   blocks the 0.1.0 milestone ("MCP server usable"), and the fix is one line plus a
   test. Best next thing.
2. **issue-008 — `CrossRefClient.Field.AFFIILIATION` is misspelt**, and Crossref
   rejects `query.affiiliation` outright. `CachingApiClient` maps a 4xx to empty, so
   an author-affiliation search silently returns nothing. Rename only (enum member is
   referenced nowhere else). Proven-to-fail test already exists:
   `TestQueryTranslation.crossrefFieldNamesAreSpeltAsCrossrefSpellsThem`.
3. **issue-014 — `EntrezEntry.getDate()` is the date PubMed *received* the record**,
   not the publication date. Live evidence: PMID 31285980 reports 2019-07-10 for a
   paper published 2015-03-23. Test `theRecordDateIsWhenTheWorkWasPublished` pins the
   wrong behaviour and is currently green — fix the code, then the test. Watch partial
   `PubDate` precision ("2019 Jun", MedlineDate "2019 Jun-Jul").
4. **issue-015 — medRxiv search is dead**, not broken code. `biorxiv.org/search/`
   returns `403 cf-mitigated: challenge` for every UA; the assembled query itself is
   correct. Needs a decision between (A) the JSON window endpoint already stubbed in
   `MedrxivClient.windowJson` + client-side term matching, (B) dropping preprint
   search — Europe PMC covers many preprints, or (C) a browser-capable fetcher, which
   means working around the operator's bot block and is not a technical call alone.
5. **issue-013 — one failing API empties `multiSearch`/`fetch`.** No per-API
   try/catch, so a single client throwing loses every source. Left open deliberately
   by the developer. Related: whether an OpenAlex over-budget refusal surfaces as a
   named failure or as "found nothing" is still untested (see plan-013 follow-up 3).
6. **debt-011 can now be closed** — `IdType.OPENALEX` is no longer declared-but-unused.
   Not done in this commit, to keep it to one client.
7. **`openalex-daily-credits` is documented in interface-002 but nothing enforces it.**
   `OpenAlexClient.creditsRemaining()` is exposed; spending it is plan-011's job.
8. **`MergePolicy` ranks OpenAlex only via `FALLBACK` and `PDF`.** Probably right for
   an aggregator whose title/date/abstract are second-hand, but it was not measured.
9. **`EuropePMCClient.getById` prefixes an id it expects bare** (`:114-115`) while
   `citesReferences`/`referencesCiting` pass already-prefixed ids into
   `references(DataSources.PMC, id)`, which prefixes again → `PMCPMC13141338`. Found
   during the OpenAlex work, not fixed (pre-existing).
10. **`EuropePMCCoreResult.getVolume()`/`getIssue()`** — the flat-vs-nested check that
    closed issue-012 for `getJournal()` was never repeated for these two.

## Version

`pom.xml` is `0.0.10-SNAPSHOT` — a patch bump I made because AGENTS.md asks for one
per change. The real question is open: feat-011 is written against **0.1.0**, whose
theme is "MCP server usable", and that is not true while issue-017 stands. Either
release 0.0.10 and let 0.1.0 come later, or fold this into 0.1.0. Per ADR-0001 the
version carries the milestone, so that is a human decision.

## Documentation debt still standing (unchanged, not started)

- `design/implementation/issues/e2e-testing-issues.md` has no number and no frontmatter;
  its claim that authors are dropped may already be fixed.
- Two debt records share number 006 (`…cached-answers-have-no-invalidation-path.md` and
  `…are-never-invalidated.md`). loom tolerates it; the numbering does not.
- `README.md:39` names the jar `bibliographic-api-client-0.0.9-SNAPSHOT.jar`, now a
  version behind the pom. Left alone until the version question above is settled.
- Javadoc: most of `client/` (the SPI) is still undocumented, and `Record.java:19` says
  `/** The */`.
- `TestSearchDedupLive` reads `inst/secrets.properties`, which is not the path the other
  live tests use (`~/.private/apis/secrets.prop`). It has never been run.
