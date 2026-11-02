# Working notes — where things stand

Handover note, 2026-11-02. Not part of the graph (no frontmatter, no loom type);
delete or absorb it whenever the items below are dealt with.

Branch `feat/mcp-usable-017-019-openalex-search`, off `main` at `6de0ec7`.
`mvn -o clean test`: **209 tests, 0 failures**. `TestOpenAlexClientEndToEnd`
(live): **14 tests, 0 failures**. `loom validate`: 0 errors. **`git push` is
outstanding, and so is pushing the five commits already on `main`** — that is the
developer's job.

## What just landed — 0.0.11

Four things, each test-first:

1. **issue-017** — `GenericSearchBuilder.withQuery` assigns the query it is given.
2. **issue-018** — `recordJson` emits `authors` (and `volume`/`issue`/`page` for a
   record that is a `PrintReference`), so an agent can attribute, cite and
   de-duplicate what it gets back.
3. **issue-019** — a `citation_style` tool chooses the style, and the `citation`
   field in `recordJson` is reachable at last. An unsupported name is refused
   rather than stored, because the renderer answers an unloadable style with an
   empty `Optional` and storing one would reproduce the bug silently. **It still
   produces no citation from the shaded jar** — see issue-022 below.
4. **OpenAlex is a search provider**, gated on credits. `openalex-daily-credits`
   is the ceiling, the new `openalex-search-credit-reserve` (default 1,000) is
   what searching may not touch, and `X-RateLimit-Remaining` is believed over both
   once a response has reported it. Below the reserve OpenAlex drops out of the
   search and the free sources answer alone; asked directly, it raises a named
   refusal instead of returning nothing.

The reasoning for reversing plan-013's "no" on search is in that plan, under
**"Search, reconsidered — 0.0.11"**. Read that rather than this file.

## Corrections to the previous version of this note

Two of the loose ends listed on 2026-11-02 were already dealt with and should not
be worked on:

- **issue-008 is closed.** The `AFFIILIATION` spelling is recorded as fixed in the
  issue's own defect table; the record was closed in `7420c3a`.
- **issue-015 is closed**, by decision rather than by code: preprint search goes
  through Europe PMC, which indexes medRxiv and bioRxiv at 99–100% coverage. The
  Cloudflare block does not need working around. `README.md` claimed otherwise
  until this commit.

## Loose ends, in the order they are worth taking

1. **issue-022 (new) — the shaded jar cannot render citations, silently.**
   GraalJS needs a second Truffle language, `regex`, and both `js-19.2.1.jar` and
   `regex-19.2.1.jar` register through a file at the same path
   (`META-INF/truffle/language`), which the shade plugin does not merge — so the
   `regex` registration is dropped and every `Record.render` in the deployed server
   returns `Optional.empty()`. Pre-existing and as old as the shaded jar; invisible
   to the tests, which run on the ordinary classpath where rendering works, and to
   the smoke test, which lists styles without rendering one. Found by driving the
   built jar over the protocol rather than by any test. Worth taking first: it is
   what stands between issue-019's fix and an agent seeing a citation.
2. **issue-020 (new) — the `search` tool offers `medrxiv`, which it cannot
   dispatch.** The schema's enum names five APIs and `dispatchSearch` knows four;
   `apis: ["medrxiv"]` is a search that can only return nothing. The fix is to
   remove it from the enum, not to add a branch — see issue-015 above. Found while
   adding `openalex` to the same enum.
3. **issue-021 (new) — the secrets file is loaded into `System.getProperties()`.**
   Every API key becomes a JVM-wide system property, and values bleed between
   successive `BibliographicApis.create` calls because the table is never cleared.
   Two-line fix; left alone because it is pre-existing.
4. **`MergePolicy` ranks OpenAlex only via `FALLBACK` and `PDF`**, and this now
   matters more than it did: OpenAlex contributes search hits that merge with
   Crossref and Europe PMC ones, so where its title, date and abstract sit in the
   ordering is a live question rather than a theoretical one. Still unmeasured.
5. **issue-013 — one failing API empties `multiSearch`/`fetch`.** Still open, still
   deliberate. The OpenAlex search path catches its own budget refusal rather than
   letting it through, so this change does not make the blast radius any larger,
   but it is the reason that catch has to exist.
6. **issue-014 — `EntrezEntry.getDate()` is the date PubMed *received* the
   record**, not a publication date.
7. **`EuropePMCClient.getById` prefixes an id it expects bare** (`:114-115`) while
   `citesReferences`/`referencesCiting` pass already-prefixed ids into
   `references(DataSources.PMC, id)`, which prefixes again → `PMCPMC13141338`.
   Pre-existing, found during the OpenAlex work, still not written up as an issue.
8. **`EuropePMCCoreResult.getVolume()`/`getIssue()`** — the flat-vs-nested check
   that closed issue-012 for `getJournal()` was never repeated for these two. More
   visible now that issue-018 puts volume and issue in the tool output.
9. **debt-011 is partly overtaken but stays open**: `IdType.OPENALEX` is
   constructed now, while `OpenCitationsCitation.pidFromPids` still labels
   `openalex:` handles `UNK`. The other two items in that record are untouched.

## Version

`pom.xml` is `0.0.11-SNAPSHOT`. The milestone question that stood here is settled:
the OpenAlex client is 0.0.10, this work is 0.0.11, and **0.1.0 now needs only
plan-008** (search deduplication) — the bugs that stopped a tool returning what it
should are cleared. The roadmap's milestone table says the same.

## Documentation debt still standing

- `design/implementation/issues/e2e-testing-issues.md` has no number and no
  frontmatter; its claim that authors are dropped is now fixed (issue-018), so it
  can probably be absorbed and deleted.
- Two debt records share number 006 (`…cached-answers-have-no-invalidation-path.md`
  and `…are-never-invalidated.md`). loom tolerates it; the numbering does not.
- Javadoc: most of `client/` (the SPI) is still undocumented, and `Record.java:19`
  says `/** The */`.
- `TestSearchDedupLive` reads `inst/secrets.properties`, which is not the path the
  other live tests use (`~/.private/apis/secrets.prop`). It has never been run.
