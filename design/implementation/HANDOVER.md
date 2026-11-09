# Handover — for the next agent

Written 2026-11-04, at the close of the debt pass. `design/implementation/PROGRESS.md`
is the running handover and stays current; this file is the same picture organised
for someone starting fresh, and can be deleted once read.

## State of the project

**Where it is.** `main`, at `d8b1316`. Version `0.2.0-SNAPSHOT`. **20 commits
ahead of `origin/main` and none of it pushed — pushing is the developer's job,
not ours, and CI has not seen any of this.**

**What works.** A Java 17 Maven build, offline by default. `mvn -o clean test`:
**340 tests, 0 failures**. `scripts/mcp-smoke-test.sh` passes: the built jar
serves the six-tool MCP surface over HTTP on 127.0.0.1 (streamable transport,
MCP SDK 2.1.0 on Jetty), and renders citations from the built artefact. Live
test tier: `./scripts/run-live-tests.sh -Dtest=<Class>` — the default build
excludes `@Category(LiveTest.class)`. `loom validate`: 0 errors.

**The shape of it.** One executable jar with dependencies nested as separate
jars (not shaded, to preserve issue-022's Truffle resources). `BiblioFacade`
is the front door — `searchableApis()` is the one
list of what can be searched, `scoredSearch(Query)` the one search, `fetch`
merged and re-keyed, identifier-shaped queries routed to fetch. Five APIs
(crossref, entrez, europepmc, openalex, medrxiv) plus opencitations and the
pmcid converter for citation/id-map roles. Six MCP tools: `search`,
`list_records`, `select`, `crawl`, `rank`, `zotero_sync`. State is a durable
SQLite repository (`repository/SqliteRepositoryStore`), not a `Session`.

## The debt pass — all nine records closed

`design/implementation/debt/` is now entirely `status: closed` (13 of 13, two
sharing the number 006 — see "known oddities" below). What each closed, and the
one thing worth carrying forward from each:

| debt | what it was | what it is now |
|---|---|---|
| 007 | unreadable cache = server won't start | `openCache` clears and rebuilds, loudly; verified end to end on the built jar |
| 009 | shared 3 s read timeout | default **15 s** (`DEFAULT_READ_TIMEOUT_MS`), clients shorten deliberately; timeouts logged as timeouts |
| 006 | empty answers cached forever | 180-day cap on the "forever" cache; empty bodies not cached; `search(refresh=true)` bypasses |
| 010 | six Europe PMC identifier loops | one `epmcTarget`; live-verified PMID/PMCID/DOI agree on 21 references |
| 005 | DOI-keyed clients miss PMID-only callers | `secondAsk` re-asks silent sources with a DOI the answering sources supplied; no mapping round trip |
| 012 | javadoc goal unbuildable | configured in the POM; `mvn -o compile javadoc:javadoc` |
| 003 | LDA refit per call | capability deleted with its dead code — see the branch below |
| 011 | unused model surface | `IdType.MID` deleted, `ARXIV` added, `arxiv:` handles typed; `PrintRecordWithCitations` deleted, `getBibliographicId` wired into `CiteProcProvider` |
| 014 | partial Entrez dates | **won't fix, on purpose** — `LocalDate` cannot say "2019, month unknown"; inventing one is worse than the imprecision |

Decisions the next agent should not re-litigate:

- **`IdType` has a principle now**: it names what sources actually emit, argued
  in `TestIdTypeMembership`. Adding a value means naming the source that emits it.
- **Empty ≠ cached, and an agent can ask again.** `search(refresh=true)` is the
  tool-surface answer to "this cached answer looks wrong".
- **A warm cache lies.** debt-005's own reproduction was a stale cache read as a
  Europe PMC capability gap. Any measurement through the cache needs a cold
  control: `rm -rf /tmp/ehcache/<test-dir>` first. Both live tests we ran this
  pass were run cold.

## The two things that should be next

1. **Push.** 20 commits, three sessions of work, CI has run on none of it.
   Everything above is unverified by anything but this machine.
2. **issue-021** (`secrets` → `System.getProperties()`): the last open issue,
   two lines, and it is a real leak — every API key becomes a JVM-wide property
   that bleeds between successive `create` calls.

Then the loose ends in `PROGRESS.md`, which are prioritised; the notable ones
are issue-026 (Europe PMC citation index — written up with a suggested fix and
an equality caution), and the two un-written-up Europe PMC oddities
(`PMCPMC…` double-prefix in `getById`; `getVolume`/`getIssue` missing the
flat-vs-nested check issue-012 closed for `getJournal`). Per AGENTS.md those
two get written up, not fixed without asking.

## The feature/topic-modelling branch

Holds `Analysis`, `Session`, `getSession`, the legacy scratch harness and the
`nlp-tools:master-SNAPSHOT` dependency — deleted from `main` when debt-003
closed, preserved here by the developer's choice. `feat-012` on the branch
describes what reinstating topic modelling would actually mean (corpus from the
store, fit keyed by corpus version + parameters, reach it without a seventh
tool). It is a branch with a description, not a plan; merging it as-is would
resurrect a second repository beside the SQLite one. The branch will drift from
main; the files it owns are ones main no longer touches.

## Known oddities (leave alone, work around, do not "fix")

- **Two debt files share number 006** (`cached-answers-have-no-invalidation-path`
  and `…are-never-invalidated`). loom tolerates it; the numbering does not. The
  second is the one that just closed.
- **`e2e-testing-issues.md`** in `design/implementation/issues/` has no frontmatter
  and its author claim is fixed (issue-018); absorbable.
- **PROGRESS.md** is deliberately not in the loom graph — no frontmatter — so
  `loom validate` ignores it. Keep it that way or give it a type.
- **`.settings/org.eclipse.jdt.core.prefs`** is tracked-but-gitignored noise from
  Eclipse's Java 17 update; deliberately left.
- **Two Maven JVMs at once** fight over the EHCache directory
  (`Persistence directory already locked`). It looks like a test failure and is
  not one. One suite at a time.
- **`jgrapht` is still a dependency** and still used by `repository/Ranking.java`
  (page rank) — do not confuse it with the deleted `Analysis`, which also used it.

## How to work here (the short version)

- AGENTS.md is the law: tests fail first, then pass; pre-existing bugs get
  documented in `design/implementation/issues/`, not fixed; branches for big
  changes; never push.
- Design docs are loom-typed markdown; address them by URI (`debt:…`,
  `feature:…`) and edit prose via `loom body`, never a file editor. See the
  `using-loom-cli` skill.
- The smoke test and the offline suite are the two green bars that matter after
  any change; live tests are opt-in and rate-limit-sensitive.
