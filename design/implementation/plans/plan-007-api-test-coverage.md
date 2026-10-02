---
status: draft
target-version: 0.4.0
tags: testing, api-clients, fixtures
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

<!-- acts as a description of what will be implemented to allow review and sign-off -->

## Feature

[HAS_FEATURE](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md) — every
retrieval-capable client it exposes must be provably fetching and parsing.
Related test scripts (prose, not typed links): `test-002-api-behaviour-tests`,
`test-005-preprint-tests`.

## Goal

Two properties, tested separately:

1. **Extraction is stable** — given a real API response, the client's parse layer
   yields the right record. Offline, deterministic, asserted, part of the default
   build.
2. **Requests still work** — the live endpoints still answer our queries and have
   not drifted. Live, opt-in, deliberately out of the default build.

## Current state (the chaos being fixed)

- `TestEntrezParsing`, `TestUnpaywallParsing`, `TestJsonParsing` are `main()`
  harnesses with no assertions: they never run under surefire and prove nothing.
- `TestEndToEnd`, `TestPubMedRestClient`, `TestConverterApiClient`,
  `TestOpenCitations`, `TestGoogleScholarClient`, `TestCombinedApi` hit live
  networks unconditionally, so `mvn test` is slow and flaky.
- Recorded fixtures exist (`efetch.xml`, `unpaywall.json`, `medrxiv*.json`,
  `medrxivJats.xml`) but almost nothing consumes them with assertions.
- The five `*ClientEndToEnd` classes are live-tier but undifferentiated from
  offline tests.

## Approach

- **No HTTP mocking framework.** The parse layer (`EntrezEntries`, `CrossRefWork`,
  `EuropePMCCoreResult`/`EuropePMCListResult`, `UnpaywallResult`,
  `OpenCitationsCitation`, `PMCIDResult`, `MedrxivRecord`, `JatsEntry`) already
  takes `JsonNode`/`Element`/`Xml` — tests feed recorded bytes straight into it.
- **Fixtures are committed raw responses** from the exact URLs the clients build,
  under `src/test/resources/fixtures/<api>/<endpoint>-<key>.<ext>`, captured by a
  dependency-free `FixtureRecorder` main and refreshed by hand when APIs change.
  Git history is the fixture changelog; filenames carry the API and query key.
- **Tier gating**: a `LiveTest` JUnit4 category marker interface; surefire
  `excludedGroups` skips those classes in the default build;
  `./scripts/run-live-tests.sh` runs exactly that tier on demand. Eclipse runs
  whatever it is pointed at regardless of category.
- **Corpus is tiny and canonical**: the preprint/published pair
  `10.64898/2025.12.12.25342129` ↔ `10.1186/s12879-026-13122-y` (PMID 41888721,
  PMC13141338, 16 authors) plus citation-rich `10.1101/2021.02.09.21250937`.
  Shared identifiers live in `TestPaperFixtures`.

## Coverage matrix (Tier 1 must assert every row)

| API | Roles | Fixtures | Outputs asserted |
|---|---|---|---|
| CrossRef | fetch/search/cites | work (journal), work (posted-content), references, query | title, authors (given/family/ORCID), date, journal vs container-title, publisher, volume/issue/pages, ISSN, DOI/PMID ids, publicationType, markup normalisation, `unstructured` reference fallback |
| Entrez | fetch/search/cites/citedBy/idMap | esearch-by-DOI, term search, esummary, efetch XML, elink citations | title, abstract, forename/lastname authors, journal title + Medline abbreviation, date, vol/issue/pages, PMID/DOI/PMCID other-ids, language, MeSH, publication types |
| EuropePMC | fetch/search/cites/citedBy | search MED, search PPR, core MED, core PPR, citations, references | `journalInfo.journal.medlineAbbreviation`, journalTitle fallback, abstract, authors+affiliations, OA, fullTextUrlList, PMID/PMCID/DOI cross-ids, `unstructuredInformation` fallback |
| Unpaywall | fetch | OA record, preprint record | `is_oa`, journal name, publisher, year, `z_authors` raw names (given/family gap asserted as-is), `best_oa_location` url/license/repository |
| OpenCitations | cites/citedBy | references, citations | DOI handles, `omid` retained when no `doi:` token, no links lacking both identifier and title |
| PMCID | idMap | DOI→PMCID, PMID→PMCID, miss | mapping triples, clean empty on miss |
| Medrxiv | fetch/search/cites | `/details` (both DOI prefixes), window page, JATS XML | latest-version selection, authors/date/abstract/category, window paging + term matching, JATS reference extraction (no id-map: `/pubs` is prefix-blind, `IdMapper` removed — see feat-009) |

## Implementation steps

- [x] Phase 1 (this pass): this plan accepted in review.
- [x] Phase 2 (this pass): infra — `LiveTest` marker, surefire `excludedGroups`
      (surefire 3.2.5 + `surefire-junit47` provider cached offline), fixtures
      directory, `FixtureRecorder` (dependency-free, `java` single-file),
      `scripts/run-live-tests.sh`.
- [x] Phase 3 (this pass): canonical corpus captured — 20 fixtures under
      `src/test/resources/fixtures/`, sanity-checked for corpus values. Capture log:
      Europe PMC requires HTTP/1.1 (JDK HttpClient/2 drops the connection);
      OpenCitations v2 requires the `doi:` scheme prefix on the id path parameter;
      `europepmc/references-ppr.json` is a documented-empty (the young
      preprint has no EPMC references) — reference parsing is exercised by
      `references-pmc.json` and `references-cited.json`; Entrez POST-form fixtures
      (esearch/efetch-by-id/elink) are deferred to phase 4 with the tests that
      consume them (`efetch.xml` already exists at the resources root).
- [ ] Phase 4: Tier-1 asserted tests per API against the matrix, in per-API
      packages (`...bibliography.<api>.Test<Api>Extraction`); convert the three
      assertion-free parsing harnesses into asserted tests.
- [ ] Phase 5: retier — `@Category(LiveTest)` on the five `*ClientEndToEnd`,
      `TestEndToEnd`, `TestPubMedRestClient`, `TestConverterApiClient`,
      `TestOpenCitations`, `TestGoogleScholarClient`; default `mvn -o test`
      becomes fully offline.
- [ ] Phase 6: declutter — migrate surviving legacy tests out of
      `uk.co.terminological.pubmedclient` into per-API packages; tombstone dead
      harnesses; record each keep/move/delete decision here.
- [ ] Phase 7: verify — `mvn -o test` green offline; live script green.

## Tests

The matrix above *is* the test plan. Tier-2 composition after phase 5 is recorded
in `TestPaperFixtures` javadoc.

## Verification

- [ ] `mvn -o test` green with no network access (offline Tier 1).
- [ ] `./scripts/run-live-tests.sh` green against live APIs.
- [ ] `Rscript …/design-check.R -d .` no new defects.

## Open questions

- GoogleScholar: scraper, rots fastest, needs a real key/behaviour decision — park
  it (no Tier-1 coverage) or delete the client?
- Phase 6 package migration: do it, or leave `pubmedclient` alone for now?
- Entrez `elink` fixtures for `cites`/`citedBy` are version-sensitive; if they
  churn, Tier-1 may assert them loosely (counts and id shapes, not exact sets).
