---
status: final
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
- [x] Phase 4: Tier-1 asserted tests per API against the matrix, in per-API
      packages (`...bibliography.<api>.Test<Api>Extraction`) — CrossRef, EuropePMC,
      Unpaywall, OpenCitations, PMCID and Entrez are green in the default build;
      Entrez POST-form fixtures (esearch/efetch/elink) are now captured (over GET —
      eutils answers identically and Tier-1 parsing only sees the response bytes) and
      consumed by `TestEntrezExtraction`. The three assertion-free `main()` harnesses
      (Entrez/Unpaywall/Json) are converted: their legacy fixtures' values are now
      asserted as pins inside the relevant `Test<Api>Extraction`, and the harnesses
      deleted. `TestJsonParsing` (a CrossRef *search-list* fixture) became
      `TestCrossRefExtraction.aSearchResponseListParsesIntoWorkRecords`, the only
      coverage of the CrossRef list parse seam.
- [x] Phase 5: retier — the five `*ClientEndToEnd` classes carry
      `@Category(LiveTest)` and are excluded from `mvn -o test` by surefire. The
      remaining named classes (`TestEndToEnd`, `TestPubMedRestClient`,
      `TestConverterApiClient`, `TestOpenCitations`, `TestGoogleScholarClient`,
      `TestCombinedApi`) contain only `main()` and no `@Test` methods, so surefire
      never runs them: the default build is already fully offline (133/133 green) and
      tagging `@Test`-less classes would be a no-op. They are a phase-6 declutter
      matter, not a retier one.
- [x] Phase 6: declutter — `uk.co.terminological.pubmedclient` is gone. Decisions:
      * **moved to `uk.co.terminological.legacy` for manual review** (all assert-less
        `main()` probes, none run by the build): `TestCombinedApi`,
        `TestConverterApiClient` (names a class no longer in main), `TestCrossRefClient`,
        `TestEndToEnd`, `TestGoogleScholarClient` (scraper parked; client still wired
        into `BibliographicApis`), `TestMedrxivParsing` (targets `MedrxivSearch`,
        which no live client uses), `TestOpenCitations`, `TestPdfUtil`,
        `TestPubMedRestClient`.
      * **re-homed, still build tests**: `TestRecordModel`/`TestRecordMerge` ->
        `bibliography.record`; `TestApiDriftParsing` -> `bibliography` (it pins
        CachingApiClient + multi-API drift).
- [x] Phase 7: verify — `mvn -o clean test` 133/133 offline; live script 8/8 green.
      Two build fixes were needed to make "live tier under Maven" real rather than
      vacuously green:
      * surefire's `<excludedGroups>` literal was invisible to `-D` override, so
        `run-live-tests.sh` ran *zero* tests; the pom now drives it from
        `${test.excludedGroups}` and the script clears it.
      * root cause of the Maven-only Entrez failure found: fluentxml ships a partial
        2006-era Saxon-PE that declares the `TransformerFactory` service, and the JDK
        only ever instantiates the *first* provider — `Saxon-HE:10.9` is now declared
        *before* fluentxml so the full jar shadows the stale one (classpath and shade
        are first-wins; **do not reorder the pom dependencies**). This also removes a
        latent landmine from the shaded runtime jar, not just the test JVM.

## Tests

The matrix above *is* the test plan. Tier-2 composition after phase 5 is recorded
in `TestPaperFixtures` javadoc.

## Verification

- [x] `mvn -o test` green with no network access (offline Tier 1) — 133/133 green
      after phase 4; no test in the default build touches the network (all live
      `@Test`s carry `@Category(LiveTest)`).
- [x] `./scripts/run-live-tests.sh` green against live APIs — 8/8 (2026-10-02).
- [x] `Rscript …/design-check.R -d .` no new defects.

## Open questions

- GoogleScholar: scraper, rots fastest, needs a real key/behaviour decision — park
  it (no Tier-1 coverage) or delete the client?
- Phase 6 package migration: do it, or leave `pubmedclient` alone for now?
- Entrez `elink` fixtures for `cites`/`citedBy` are version-sensitive; if they
  churn, Tier-1 may assert them loosely (counts and id shapes, not exact sets).
  **Resolved (phase 4):** `pubmed_pubmed_refs` answers with plain PMIDs (not PMC ids)
  and the exact set drifts with the live citation graph, so `TestEntrezExtraction`
  asserts the *shape* — every row starts from the canonical PMID and points at a
  numeric PubMed id — not a fixed set.

## Findings (recorded by the phase-4 extraction tests, each pinned to its source)

- Title orthography is per-source and now deliberately pinned, not normalised in the
  parse layer: EuropePMC and Entrez append a trailing `.` to article titles; CrossRef
  keeps a Unicode en dash in the date range (`2006–2022`) where EuropePMC/Unpaywall-
  preprint/openRxiv use an ASCII hyphen; CrossRef title-cases container titles where
  EuropePMC sentence-cases them. Case/dash/punctuation folding is a merge-layer
  concern, not an extraction one.
- `CrossRefWork.getOtherIdentifiers()` is hardcoded empty, so CrossRef's own
  `identifier[]`/`alternative-id` arrays (PMID etc.) never surface — pinned as a known
  model limitation rather than fixed.
- Publication types are normalised into the CSL vocabulary (`journal-article` ->
  `article-journal`); preprint `posted-content` passes through unchanged.
- Unpaywall `z_authors` have null `given`/`family` for this corpus; the raw
  `raw_author_name` split is what surfaces a name (pinned).
- OpenCitations resolves incoming citations to the **version of record** DOI
  (`10.1136/bmj.n579`) even though the recorded handle still carries the preprint's
  `omid:`; a citing work with no DOI surfaces its `omid`/`openalex` handle as an
  `UNK` identifier rather than being dropped.
- Entrez `getDate()` reads `PubMedPubDate[@PubStatus='pubmed']` (MEDLINE index date,
  27th) not the journal `PubDate` (26th) that Unpaywall/EuropePMC report.

