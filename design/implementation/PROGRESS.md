# Working notes — where things stand

Handover note, started 2026-11-02 and kept current. Not part of the graph (no
frontmatter, no loom type); delete or absorb it whenever the items below are
dealt with.

As of 2026-11-04: `pom.xml` is **`0.2.0-SNAPSHOT`**. `mvn -o clean test`:
**333 tests, 0 failures**. `scripts/mcp-smoke-test.sh` passes, including the
citation render check. `loom validate`: 0 errors. **`git push` is outstanding**
— that is the developer's job, and CI has not run on any of this.

## 0.1.0 — "MCP server usable"

The milestone is reached. Neither 0.0.10 nor 0.0.11 was ever released, so the
OpenAlex client and the tool fixes that carried those numbers ship together as
0.1.0; two commit messages still say 0.0.11, which records the order things were
done rather than a release.

What it contains, in the order it happened:

1. **OpenAlex as a provider** (feat-011, plan-013). Five SPI roles, Unpaywall
   retired. Registered for search as well, behind a credit gate:
   `openalex-daily-credits` is the ceiling and `openalex-search-credit-reserve`
   (default 1,000) is what searching may not touch, so a day of searches cannot
   starve the 1-credit fetch and citation calls. Below the reserve OpenAlex drops
   out of the search and the free sources answer alone.
2. **issue-017** — `GenericSearchBuilder.withQuery` assigns the query it is given.
3. **issue-018** — `recordJson` emits `authors`, and `volume`/`issue`/`page` for a
   record that carries print coordinates.
4. **issue-019** — a `citation_style` tool chooses the style; records returned
   afterwards carry a rendered `citation`.
5. **issue-022** — the build no longer shades. The dependencies live in
   `target/lib/` and the manifest names them, so each keeps its own resources and
   GraalJS gets its `regex` language back. This is what made (4) visible at all:
   before it, every `Record.render` in the built server returned empty, silently.
6. **issue-020** — `BiblioFacade.searchableApis()` is the one list of what can be
   searched, and the tool schema builds its enum from it.
7. **issue-023** — a query that is nothing but an identifier is fetched rather
   than text-matched. `10.1136/bmj.n579` matched 305 works before this.
8. **plan-008** — the dedup and RRF ranking were already implemented; the evidence
   was missing. Six offline tests now assert the grouping, and the live test was
   rescoped from an unbounded "SARS-CoV-2" search (ten minutes) to one author in
   one year (9.5 s).

## Loose ends, in the order they are worth taking

1. **issue-024 — `fetch` answers under an id it chose.** `EntrezClient` keys its
   answer by the PMID it resolved to while every other client keys by the
   reference it was asked about, so one DOI comes back as two records and the
   Entrez contribution never reaches the merge policy beside the others. The MCP
   `fetch` tool has this too, not only search. `BiblioFacade.search`'s identifier
   path merges them as a workaround.
2. **issue-021 — the secrets file is loaded into `System.getProperties()`.** Every
   API key becomes a JVM-wide system property, and values bleed between successive
   `BibliographicApis.create` calls. Two-line fix.
3. **debt-013 — `Record.render` swallows every exception and logs nothing.** One
   WARN line. It is what let issue-022 hide a comprehensively broken citation
   engine behind a missing JSON field.
4. **Crossref dominates a search and is its least precise contributor.** On the
   live dedup query it supplies 20 of 28 records through fuzzy author matching and
   a deposit-date filter, and in one run it returned 0 instead of 20 — a transient
   failure that `CachingApiClient` turns into an empty result (issue-013).
5. **`MergePolicy` ranks OpenAlex only via `FALLBACK` and `PDF`**, which matters
   more now that OpenAlex contributes search hits that merge with the others.
   Still unmeasured.
6. **RRF `K = 5` was tuned for three search APIs** and there are now four. Noted in
   plan-008, not re-measured.
7. **issue-013 — one failing API empties `multiSearch`/`fetch`.** Still open, still
   deliberate; (4) is what it costs in practice.
8. **issue-014 — `EntrezEntry.getDate()` is the date PubMed *received* the
   record**, not a publication date.
9. **`EuropePMCClient.getById` prefixes an id it expects bare** (`:114-115`) while
   `citesReferences`/`referencesCiting` pass already-prefixed ids into
   `references(DataSources.PMC, id)`, which prefixes again → `PMCPMC13141338`.
   Pre-existing, still not written up as an issue.
10. **`EuropePMCCoreResult.getVolume()`/`getIssue()`** — the flat-vs-nested check
    that closed issue-012 for `getJournal()` was never repeated for these two.
    More visible now that issue-018 puts volume and issue in the tool output.
11. **debt-011 is partly overtaken but stays open**: `IdType.OPENALEX` is
    constructed now, while `OpenCitationsCitation.pidFromPids` still labels
    `openalex:` handles `UNK`.

## A trap when running the tests

Two Maven JVMs at once fight over the EHCache directory: the second dies with
`StateTransitionException: Persistence directory already locked by another
process: ~/.cache/ehcache/entrez`. It looks like a test failure and is not one.

Related, and unexplained: `TestRecordMerge.aPostedContentRecordIsOwnedByItsPreprintServer`
failed **once**, in a full-suite run that overlapped a second Maven process, and
has not reproduced in a dozen runs since. The symptom — Europe PMC's title winning
over medRxiv's — is what a rank *tie* would produce, since the tie-break is
alphabetical on the value. Worth remembering if it recurs.

## Documentation debt still standing

- `design/implementation/issues/e2e-testing-issues.md` has no number and no
  frontmatter; its claim that authors are dropped is fixed (issue-018), so it can
  probably be absorbed and deleted.
- Two debt records share number 006 (`…cached-answers-have-no-invalidation-path.md`
  and `…are-never-invalidated.md`). loom tolerates it; the numbering does not.
- Javadoc: most of `client/` (the SPI) is still undocumented, and `Record.java:19`
  says `/** The */`.
