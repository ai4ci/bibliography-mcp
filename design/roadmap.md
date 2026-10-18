---
target-version: 0.1.0
status: draft
tags: roadmap, mcp, opencitations, bibliographic-apis
---

# Roadmap

Ideas and options for the bibliographic-api-client MCP server, in enough
detail to choose between alternatives before implementation. This is a
working document: options are recorded as they arise, and graduate into
`design/features/`, `design/implementation/plans/` or `design/implementation/issues/`
when a decision is made (see [Conventions](#conventions)).

## How to use this document

Ideas are numbered. Each has a status and, where a choice exists, a table of
**options** with effort/risk/benefit, and a recommendation. The developer
decides; an agent should not start implementing an idea whose status is
`proposed` without confirmation.

| Status | Meaning |
|--------|---------|
| `proposed` | recorded, not yet decided |
| `accepted` | decided — ready for a plan under `design/implementation/plans/` |
| `in-progress` | has a plan; work started |
| `done` | implemented and documented |
| `deferred` | deliberately not now; kept for the record |
| `rejected` | considered and declined — keep the reasoning |

Effort is relative (S/M/L), not a timescale.

---

## 1. OpenCitations Meta enrichment — `proposed`, effort M

### Context

`OpenCitationsClient` currently uses only the **Index v2** API
(`api.opencitations.net/index/v2/citations|references/{id}`, see
[src/main/java/uk/co/terminological/bibliography/opencitations/OpenCitationsClient.java](/src/main/java/uk/co/terminological/bibliography/opencitations/OpenCitationsClient.java)).
Index v2 returns citing/cited entities as space-separated PID lists, e.g.
`omid:br/061301957054 doi:10.1136/bmjqs-2018-008370 openalex:W2910707576`.
`OpenCitationsCitation.doiFromPids` extracts the `doi:` token and returns
`null` when there is none, which makes the `CitationLink` incomplete — and
`Session.add(CitationLink)` **silently skips incomplete links**. Citations
whose endpoints are OMID/OpenAlex-only (books, chapters, non-DOI venues) are
therefore discarded.

The **Meta API** (`api.opencitations.net/meta/v1`, verified live) is a
metadata layer over the same corpus:

| Operation | Gives |
|-----------|-------|
| `/metadata/{ids}` | one row per entity: `id` (all known PIDs), `title`, `author`, `pub_date`, `issue`, `volume`, `venue`, `page`, `type`, `publisher`, `editor` |
| `/author/{id}` | all works by an ORCID / OMID person |
| `/editor/{id}` | all works edited by an ORCID / OMID person |

Verified properties that matter here:

- The `id` field is a complete equivalence class of PIDs:
  `doi:… openalex:… pmid:… omid:br/…` — an id-mapping source broader than the
  PMC IDConverter (adds openalex, omid, isbn, issn; covers books; works when
  there is no DOI at all).
- Accepted input prefixes include `pmid:` and `pmcid:` and `openalex:` even
  though the prose docs advertise only doi/issn/isbn/omid.
- `author` and `venue` values embed identities:
  `Challen, Robert [orcid:0000-0002-5504-7768 omid:ra/06320378712]`,
  `BMJ Quality & Safety [issn:2044-5415 …]`.
- `/author/orcid:0000-0002-5504-7768` returned 22 works with dates and PIDs.
- Metadata only: **no abstracts, no citation links**.

### Problem statement

Citations are being dropped when the endpoint has no DOI, and id mapping
(and therefore record merging in `Session`) is limited to what the PMC
IDConverter can express.

### Options

| # | Option | Gives | Effort | Risk |
|---|--------|-------|--------|------|
| A | **Meta as an idMap source only** — one client operation `metadata(ids)` mapped to `RecordReferenceMapping`, registered alongside `"pmcid"` in `BiblioFacade.defaultApis()` | Recovers OMID/OpenAlex id equivalences; feeds `Session.merge(…)` and `findAlternateIds()`; **directly rescues the dropped citation links** once the omid endpoints resolve | S | Low — reuses the existing mapping contract |
| B | **Resolve dropped links during citation expansion** — in `OpenCitationsCitation`/`BiblioFacade`, resolve omid-only endpoints via Meta before constructing the `CitationLink` | Same rescue as A but eagerly, at the point of loss; no change to the mapping API | S–M | Medium — extra API call per unresolved endpoint; interacts with caching and rate limits |
| C | **Full Meta client** — `metadata` + `author` + `editor`, plus a new MCP tool `author_works` (ORCID/OMID → works) | New author-centric capability (author bibliographies, co-authorship, self-citation analysis with Index v2's `author_sc`) | M | Low–Medium — new tool surface, new result shape |
| D | **Meta as a RecordFetcher source** — fetch details for records no other API resolved | Fills titles/venues/types for books and chapters | M | Medium — **no abstracts**, so it cannot replace CrossRef/EuropePMC/PubMed; partial record shape |
| E | **Venue/journal analysis** — parse ISSN pairs from `venue`, combine with Index v2 `/venue-citation-count/issn:…` | Journal-level analysis distinct from per-article tools | M | Medium — new result type and analysis path |
| F | **Defer entirely** — keep PMC IDConverter as the only id mapper | No new code | — | — |

### Recommendation

Do **A** first: one operation, reuses the existing `RecordReferenceMapping`
contract and the mapper seams in `BiblioFacade`/`Session`, and it fixes the
silent data loss. Consider **B** only if A's mapping pass turns out not to
happen early enough in the workflows (A depends on `find_alternate_ids` being
called, whereas B rescues links unconditionally).

**C** is the interesting genuinely-new capability and is worth a separate
idea/plan — do not bundle it into A.

**D** is not a substitute for the existing fetchers and should probably wait
until there is a concrete need for book/chapter records. **E** depends on
deciding that journal-level analysis is in scope.

### Open questions

- Should the Meta client be a new class (`OpenCitationsMetaClient`) or a
  second operation set on `OpenCitationsClient`? Both APIs are RAMOSE, share
  a host and the 180 req/min limit; sharing the client would share the
  token bucket, which may or may not be correct (they are separate services).
- Meta responses take 3–6 s — the same read-timeout override and caching
  approach as Index v2 applies.
- Which PID should a rescued `CitationLink` prefer when several are present
  (DOI > PMID > PMCID > OMID)? The internal model is `IdType`-based with a
  single identifier per reference.
- Index v2 flags `author_sc` / `journal_sc` per citation but the model drops
  them — is self-citation analysis wanted (would need extending `CitationLink`)?

### Linked artefacts

Option A has been written up in the framework's own form:

- [HAS_FEATURE](/design/features/feat-004-opencitations-meta-enrichment.md)
- [HAS_PLAN](/design/implementation/plans/plan-001-opencitations-meta-idmap.md)
- [HAS_PROTOTYPE](/design/prototypes/prototype-001-opencitations-meta-pids.md)

Related records:

- [issue-001](/design/implementation/issues/issue-001-non-doi-citation-links-dropped.md)
- [debt-002](/design/implementation/debt/debt-002-opencitations-queries-dois-only.md)
- [ADR-003](/architecture/decision-records/adr-003-opencitations-index-v2.md)

---

## 2. Zotero as a curated repository — `done`, effort L

### Context

`src/main/java/uk/co/terminological/bibliography/zotero/ZoteroClient.java` is an
empty stub: Zotero is unimplemented. The Zotero desktop client exposes a
**local API** (`http://localhost:23119/api/`, Web API v3 semantics, no
rate limits, offline, unauthenticated for reads) which the developer intends to
use exclusively; the Web API is not used and therefore group libraries are out
of scope.

Verified live against the developer's library (2026-09):

| | |
|---|---|
| Connection | API v3, schema 44, `Zotero-Server-ID` `WPvnUeeIMBbg`, Zotero 10+ (local writes available) |
| Library | 2060 top-level items (4925 including attachments/notes/annotations), 128 collections (16 top-level, 112 nested), 3271 tags, library version 50 |
| Sanctioned write target | collection `api-test` (`TB35FFX6`), empty |
| Item types (all 2060, CSL vocabulary) | `article-journal` 1549 (75.2%), `webpage` 135 (6.6%), `report` 98 (4.8%), `document` 88 (4.3%), `paper-conference` 42 (2.0%), `article` 31 (1.5%, from Zotero `preprint`), `manuscript` 31 (1.5%), `book` 27 (1.3%), `software` 22 (1.1%), `chapter` 11, `article-newspaper` 11, `post-weblog` 6, `entry-encyclopedia` 4, `thesis` 3, `dataset` 2 — 15 CSL types from 16 native ones |
| Identifier coverage (all 2060) | **DOI 52.6%**, ISBN 1.2%, PMID-in-extra **0%**, no DOI/ISBN/url at all **30.8%**; venue field 76.9%, `abstractNote` 80.8% |
| Collection membership | 1.06 collections per item on average; 177 items in no collection; 10.8% in ≥2 |
| Local quirks | `date` is free text (`"2013"`, `"2013-05"`, `"2026-09-16"`); Zotero maps `preprint`→`article` in CSL export although CSL has `posted-content`; 2 top-level items are bare `attachment`s; counts include non-bibliographic types that must be tolerated |
| Not supported locally | `/items/new` (404 — build templates from `/itemFields`), Atom (501), partial file uploads (405) |
| Sync primitives | `since=`, `format=versions` (all 2060 in one request), `format=keys` |

Zotero contributes **no citation graph** — it is a curated store, not a citation
source. It is also a **live research library**, so write operations are
consequential and sync to zotero.org.

### Problem statement

Two workflows require Zotero:

1. **Discovery from a curated seed.** "Here is a collection I built; find
   related work and add the most relevant items into a sub-collection." This
   needs reading a collection, expanding and ranking related work, deciding
   novelty against what is already held, then creating a sub-collection and
   adding items.
2. **Library maintenance.** "Scan my library and use the bibliographic APIs to
   refresh journal metadata and PDFs for existing entries."

Both are bridges between the developer's curated library and the working set of
records discovered by the other APIs.

### Conceptual question: is a Zotero collection the same as a `Session`?

No — they are different lifecycle stages of the same underlying work-references,
and the design should keep them distinct rather than attempting to sync them:

| | `Session` (working set) | Zotero collection (curated corpus) |
|---|---|---|
| Purpose | everything found so far, for graph building and analysis | the developer's chosen, human-facing knowledge base |
| Optimises for | recall | precision and longevity |
| Contents | may include duplicates, non-DOI records, partial metadata, records from many APIs | one item per work, with notes, tags, attachments, relations |
| Identity | internal uuid, merged by identifier | stable `itemKey` + version, with the user's own edits |
| Lifetime | ephemeral (persistable) | long-lived, synced, backed up |
| Authority | none — a scratch space | the user's own curation decisions |

The consequence: transfer between them is an explicit **promotion of a
selection**, never a wholesale sync. A `Session` is a superset that may contain
candidate material; a collection is what survived triage. Which is why the
workflow needs a *selection* step rather than a copy of everything.

### Decisions taken (developer steer)

These are settled; the options below only cover how to implement them.

- **Relevance is exposed, not decided.** Page rank and topic similarity are
  both reported with their own named scores and the agent picks, in discussion
  with the user. Relevance is *not* mechanistic, and the measure must be
  **extensible**: a future locally-hosted LLM embedding / vector-database
  scorer ([idea 4](/design/roadmap.md)) has to slot in without changing the
  tool contract. Practically: tools return a labelled score set
  (`{"pageRank": …, "topicSimilarity": …}`), never a single opaque
  "relevance" number.
- **Tagging, not selection state.** There is no need to audit which records
  were selected, so `Session` gains no provenance/selection fields. Instead the
  agent records its own decisions by **tagging items in Zotero**, and the
  agreed ways of working decide the tagging convention. The MCP surface must
  therefore support tag writes that *preserve* existing tags rather than
  replacing them.
- **Local only.** Local API by default, fail fast if unavailable; the Web API
  is an explicit opt-in, so group libraries are out of scope.
- **Writes off by default**, restricted to a configured target collection
  (`api-test`).
- **Enrichment may update** `abstractNote`, `DOI`, `url`, `publicationTitle`,
  `date`, **`creators`** and **tags** — but see the overwrite-semantics
  question below, because `creators` and tags can hold the user's own curations.
- **Item type needs a framework-level answer**, not a Zotero-local heuristic —
  see [idea 3](/design/roadmap.md).
- **Duplicates are tolerated in the Zotero path only.** Zotero
  detects duplicates and gives the user a UI to resolve them, so an occasional
  duplicate is an acceptable outcome. Publish checks identifier and title+year
  before creating, then accepts what it cannot match; tools report
  disagreements rather than silently choosing a winner. `Zotero.itemKey` is
  treated as **linkage/provenance**, not as an identifier of the work, because
  it is scoped to one Zotero instance. This does *not* relax the discovery path:
  records from several APIs still have to merge into one predictable source of
  truth, fixed up front in [idea 5](/design/roadmap.md).
- **Never overwrite a Zotero item with API-fetched values — that is where
  curation is enforced.** An item may be scraped from a publisher page and broken,
  or hand repaired, and the data does not say which: `libraryCatalog` names a
  publisher web page for 240 of a 300-item sample and `dateModified` differs from
  `dateAdded` on 89/300, but neither attributes the edit to a person. So on the
  write path: where the API data **agrees** with the item, additional fields may
  be filled in; where it **disagrees**, import the API record as a new item and
  let Zotero's duplicate detection and the user resolve it. A Zotero record
  therefore competes on the same per-field merge table as any other source
  rather than winning by virtue of being curated.
- **The model change is the fundamental blocker** — see [idea 3](/design/roadmap.md)
  for the minimum field set that supports ~90% of the library's content.

### Options

**Read bridge**

| # | Option | Gives | Effort | Risk |
|---|--------|-------|--------|------|
| R1 | `ZoteroClient` implements `Searcher`/`RecordFetcher`, registered as source `"zotero"` in `BiblioFacade`; `/items/top` by default; lossy `Record` mapping with raw JSON retained (`ExtensibleJson` pattern) | Existing search/fetch/session tools work on Zotero unchanged; Zotero records merge with other APIs by DOI/ISBN | M | Low — read-only |
| R2 | A separate Zotero type with its own tool surface, no `Record` mapping | Accurate collections/tags/notes/attachments, but no merging or novelty comparison | M | Low |

**Search-first / novelty**

| # | Option | Gives | Effort | Risk |
|---|--------|-------|--------|------|
| S1 | In-memory library index (one unpaginated read, refreshed by `since=`) used to annotate every external search result with `inLibrary` + Zotero key | "Search Zotero first" without a second round trip; the agent can rank novelty | M | Low |
| S2 | A separate `zotero_check` tool the agent calls around its searches | Less machinery, but the agent must remember to call it | S | Low |

**Write bridge**

| # | Option | Gives | Effort | Risk |
|---|--------|-------|--------|------|
| W1 | `create_subcollection` + `add_items_to_collection` (batch ≤50), idempotent by DOI (check before create), target collection from config (`api-test`), writes disabled unless enabled | The primary workflow end to end | M | Medium — mutates a live library |
| W2 | Full CRUD: update/move/delete/retag | Power for maintenance workflows | L | High — deletes and edits are irreversible-ish in a synced library |

**Enrichment**

| # | Option | Gives | Effort | Risk |
|---|--------|-------|--------|------|
| E1 | Dry-run report: for each item, diff local fields against CrossRef/EuropePMC/Unpaywall and report gaps (missing abstract, stale venue, no PDF); no writes | Immediately useful, zero risk | M | Low |
| E2 | E1 + apply approved field updates per item (`PATCH`, version-preconditioned) | Keeps the library accurate | M | Medium |
| E3 | E2 + PDF retrieval and attachment (3-phase upload) | Complete maintenance | L | Medium–High |

**Mode and configuration**

| # | Option | Gives | Effort | Risk |
|---|--------|-------|--------|------|
| C1 | Extend the existing properties file: `zotero.mode=local` (default) / `off`, `zotero.allow-writes=false` (default), `zotero.write-collection=<key>`; web mode an explicit opt-in | Least-privilege defaults, no new config mechanism | S | Low |
| C2 | Separate Zotero config file | Cleaner separation, another file to keep in sync | S | Low |

### Recommendation

Adopt R1 + R2-lite (records through `Record` for merging; a small Zotero-shaped
tool surface for collections/tags/attachments that `Record` cannot express),
S1, W1, E1, and C1. Local mode only. Writes default off with `api-test` as the
configured target, so the live library is only touched where the developer has
explicitly sanctioned it.

Ordering: **R1+S1** (read and novelty) → **W1** (the primary workflow) →
**E1** (zero-risk maintenance value) → **E2/E3** (apply). W2 stays unadopted
until there is a concrete need.

Worth noting for scoping: R1+W1 is mostly a *bridge* over capabilities the
project already has — citation expansion, page rank, topic modelling and
ranking all exist; what is missing is import, novelty annotation, and
publishing into a sub-collection.

### Open questions

- **Overwrite vs fill for enrichment.** Writing `creators` or `tags` can destroy
  the user's own curation (renamed authors, added tags, provenance tags the
  agent itself wrote). Recommendation: **fill gaps and union tags** by default,
  and treat "replace this field" as a separate explicit action with a dry-run
  diff; a destructive write to a live, synced library is the least reversible
  thing in this design.
- **Tag write mechanics.** Local tag updates are read-modify-write of an item's
  `tags` array with version preconditions, so a concurrent edit returns `412`
  and must be retried; and the MCP surface should expose "add/remove tags"
  rather than a raw item `PATCH` that an agent could misuse into a replace.
- **Publication type / venue kind.** Zotero's `bookSection`, `preprint`,
  `report`, `blogPost` and `webpage` are genuinely different things from
  `journalArticle`, and `Record` cannot express that today — see
  [idea 3](/design/roadmap.md). This affects ranking quality, publish mapping
  and enrichment (a report has no `publicationTitle` to refresh).
- **Matching has to work without DOIs.** Only 52.6% of items carry a DOI and
  30.8% have no DOI, ISBN or URL at all, so DOI-keyed matching would report
  roughly half the library as "not held" and could not enrich half of it. A
  title+year (+ first author) match key is therefore **required**, not an
  optional fallback: the library index needs composite keys, publish
  idempotency needs it too, and enrichment must be honest that DOI-less items
  cannot be refreshed from DOI-based APIs. This is also the strongest argument
  for the future embedding scorer in [idea 4](/design/roadmap.md), which would
  address near-duplicate matching directly.
- **Tagging convention.** Left to the agent's judgement by design, but the
  first convention adopted becomes a de facto contract for other agents —
  worth agreeing a default before the first write.
- **Testing.** Fixtures for mapping/parsing; read-only live checks; writes only
  to `api-test` and only with approval; a separate Zotero instance before file
  uploads (`E3`), which are the least reversible write path.
- **Version namespacing.** Any cached index or version state must be
  partitioned by `Zotero-Server-ID`; local versions are unrelated to Web API
  versions.

### Linked artefacts

- [HAS_FEATURE](/design/features/feat-005-zotero-curated-repository-bridge.md)
- [HAS_TEST](/design/test-scripts/test-003-zotero-bridge-tests.md)

Option **E1/E2** (report, then apply) is split out as
[HAS_FEATURE](/design/features/feat-008-clean-up-collection-metadata.md), rescoped to one
collection and made to include the write;
option **C** (the full Meta client with author traversal) remains unstarted.

---

## 3. Minimum data model change to support curated-library content — `done`, effort S–M

Draft thoughts, written after reading `Record` against the developer's actual
Zotero library. The goal is the **smallest** model change that supports ~90% of
that content and still fits the citation-graph use case the framework exists for.

### What `Record` carries today

| Dimension | Present | Missing |
|---|---|---|
| Identity | one `(IdType, id)` + `getOtherIdentifiers()` | `IdType` = `PMCID, PMID, DOI, MID, UNK` — no ISBN, ISSN, arXiv, OpenAlex, OMID |
| Text | title, abstract, date (`LocalDate`), licences | language, keywords/Mesh |
| Venue | `Optional<String> getJournal()` | venue as an entity (ISSN, venue ids) |
| Creators | `List<Author>` = ORCID + names + affiliations | creator role (author vs editor), publisher |
| Files | one `Optional<URI> getPdfUri()` | attachment lists |
| Print detail | volume, issue, page, year — on the **separate** `PrintReference` interface only | — |
| Raw payload | `Raw`/`ExtensibleJson` on specific implementations | lost on merge into `MergedRecord` |
| Provenance | only implicit in the Java class (`CrossRefWork`, `EntrezEntry`, …) | not visible through the interface, not retained on merge |
| **Kind of work** | **nothing anywhere** | publication type, container/parent work |

An agent sees 11 fields: `id, idType, title, abstract, journal, date, url, PMID, DOI, PMCID, citation`.

### What "support" means here

Sufficient: the model can **identify** an item, **classify** what kind of thing
it is, and carry the fields that kind actually has without silently discarding
them.

Not required: full fidelity to every Zotero field. Notes, tags, collections,
relations, attachments and full text belong to the *repository*, not the
record — a `Record` is a statement about a work, not a library entry.

### Where the 90% lands (measured, all 2060 top-level items)

| CSL type | count | % | cumulative | needs beyond today |
|---|---:|---:|---:|---|
| `article-journal` | 1549 | 75.2 | 75.2 | type only — `journal` already holds the container |
| `webpage` | 135 | 6.6 | 81.8 | type only — `url` already held |
| `report` | 98 | 4.8 | 86.6 | type + publisher/institution — **tier 1** |
| `document` | 88 | 4.3 | **90.9** | type only (Zotero's generic document; bare attachments map here) |
| `paper-conference` | 42 | 2.0 | 92.9 | type + proceedings title (container) |
| `article` (from Zotero `preprint`) | 31 | 1.5 | 94.4 | type — **needs the native type retained**, Zotero maps `preprint`→`article` |
| `manuscript` | 31 | 1.5 | 95.9 | type only |
| `book` | 27 | 1.3 | 97.2 | type + ISBN |
| `software` | 22 | 1.1 | 98.3 | type + url/version |
| `chapter` | 11 | 0.5 | 98.8 | type + container (book) + ISBN |
| `article-newspaper` | 11 | 0.5 | 99.3 | type + container |
| `post-weblog`, `entry-encyclopedia`, `thesis`, `dataset` | 15 | 0.7 | 100 | type (+ container for the encyclopedia entry) |

The shape of the answer: **one new field buys the first 90.9%**, and the
remaining 9% is a heterogeneous tail where each type needs one extra thing
(container, ISBN, publisher, version).

### Minimum change (agreed tier 1)

Four additive items, no structural refactor. Decisions settled with the
developer 2026-09:

1. **Publication type on `Record`** — `Optional<String> getPublicationType()`,
   carrying the **CSL type vocabulary** as a **string, not a Java enum**, so
   unknown types round-trip and no recompile is needed to support them.
2. **`Provenance` on `Record`** — carrying `source`, **`nativeType`** (the
   source API's own vocabulary verbatim), `fetchedAt` and `url`. The native type
   lives here rather than as a separate `Record` field.
3. **`IdType.ISBN`** — one enum value, so books and chapters are addressable at
   all (20 of 38 book/bookSection items carry one).
4. **Publisher / issuing body** — `Optional<String> getPublisher()`. Wanted
   because reports are needed (98 items, 4.8%). Zotero's own CSL export settles
   the mapping: it maps a report's `institution` to CSL `publisher` (verified:
   a sample report exports `publisher: "Department of Health and Social Care
   (UK)"` while `institution` is the native field), and books/chapters use
   `publisher` natively (82% of book/bookSection items). One canonical field,
   two native sources.

Optionally, and cheaply: document `getJournal()` as "container title" and add
`Optional<String> getContainerTitle()` whose default delegates to
`getJournal()`. Zotero's CSL export confirms the shape: a book chapter exports
`container-title` = the book title, while an article uses it for the journal.

### Availability in the existing clients (why this is cheap)

The data is mostly already in hand — it is *captured* in payloads but not
exposed through the interfaces:

| Source | Type | Publisher / issuing body | Notes |
|---|---|---|---|
| Zotero | `data.itemType` (native) + CSL export mapping | `publisher` (books), `institution` (reports) | both already in the JSON we receive |
| CrossRef | `type` in the payload (e.g. `journal-article`, `book-chapter`) | `publisher` — **declared but commented out** at `CrossRefWork.java:103` | `CrossRefWork` extends `ExtensibleJson`, so both are one-line dynamic accessors in the style of the existing `getJournal()` |
| Europe PMC | `pubTypeList.pubType` (array, e.g. `["Research Support, Non-U.S. Gov't", "research-article", "Journal Article"]`) on fetched records | not provided | in the payload, not currently read |
| Entrez | `PublicationTypeList` — **not parsed at all** | not provided | needs new parsing |
| OpenCitations | none | none | PID lists only |

### Tier 2 (the last ~9%)

Container title as a first-class idea, publisher/institution, edition, and
venue identity (ISSN) for journal-level analysis. Also worth considering:
putting type on `RecordReference` as well as `Record`, so citation-graph nodes
can be filtered by kind without loading full records.

### Explicitly out of scope

Notes, tags, collections, relations, attachments (beyond a single PDF URI),
full text, child items, the Zotero `itemKey` **as identity**, and version /
precondition state. These belong to the library/repository layer — which is
why the Zotero bridge needs its own tool surface in addition to `Record`.

### Why this is safe for the citation-graph use case

The graph needs *stable identity* and *edges*; type and container are
annotations on the nodes. So the change is additive: it does not alter existing
`IdType` semantics, the identity/merge path, or the citation-link model. The
only graph-relevant addition is `ISBN`, which *extends* identity coverage rather
than changing it. That is what makes this the minimum change rather than a
model rewrite.

### Provenance: why it earns its place

The developer's point is right, with a sharpening: provenance is partly carried
today by the Java class (`CrossRefWork`, `EntrezEntry`, `ZoteroItem`…), but that
is invisible through the `Record` interface, requires `instanceof` to read, and
is **lost when records merge** into a `MergedRecord` — precisely when knowing
the source matters most.

Two things need it:

- **Enrichment** must distinguish "the user curated this value in Zotero" from
  "a third party asserts this value", because that decides fill-versus-overwrite.
- **Reporting/duplicates** should be able to say *where* a claim came from when
  two records disagree, given that duplicates are tolerated rather than resolved.

Minimum: a source identifier (the API name). Recommended:
`Provenance{source, nativeType, fetchedAt, url}` — small, and `nativeType` is
what makes the two-level type model work. Note that a Zotero `itemKey` belongs
*here* (linkage to a specific repository instance, keyed by `Zotero-Server-ID`)
rather than in `IdType`, because it is instance-scoped and not an identifier of
the work.

### Merge policy: deliberately deferred

Developer steer: Zotero detects duplicates itself and provides a UI for a human
to resolve them, so a duplicate entry is an acceptable outcome — not the worst
one. Therefore:

- **no precedence engine** is built now; the model does not need to decide which
  API's title wins;
- publish checks DOI / ISBN / title+year before creating and **accepts**
  occasional duplicates, leaving resolution to Zotero's duplicate detection;
- where two records disagree, tools should **report the disagreement** rather
  than silently picking one.

This removes the merge-policy dependency previously floated as a prerequisite
for the Zotero work.

### Type on `RecordReference`: decided against

Checked rather than assumed. Citation-link payloads carry a publication type in
exactly one of the four sources:

| Citation source | Carries a type? |
|---|---|
| OpenCitations Index v2 (the main citation provider) | no — `citing`/`cited` are PID lists |
| Entrez eLink | no — link ids only |
| CrossRef | no — the work's `type` is not exposed and the reference arrays carry none |
| **Europe PMC** | **yes** — `citationType`, a semicolon-separated PubMed type list (`"systematic review; review-article; journal article"`) on both `/references` and `/citations`; not currently surfaced because `EuropePMCReference`/`EuropePMCCitation` declare no fields |

So the field would be populated from a minority source, in a different
vocabulary (PubMed publication types, not CSL), and only where Europe PMC
supplies the edge. The graph does not need it — nodes acquire a type when they
are fetched, which the session workflow does anyway — so type stays on `Record`
only. Worth recording for later: if typed *graph edges* are ever wanted,
Europe PMC's `citationType` is the one place the data exists.

Incidentally the same probe showed Europe PMC reference rows also carry
`volume`, `issue`, `pageInfo`, `issn` and `essn` — print detail and venue
identifiers that the citation-link model currently discards (relevant to tier 2).

### Remaining open questions

- Is `fetchedAt` wanted on provenance, or is that cache metadata the EHCache
  layer already owns?
- Do we want `publisher` to keep the native source field distinguishable
  (`institution` vs `publisher`) in `Provenance`, or is the canonical value
  enough?
- CrossRef's `publisher` is declared-but-commented: un-comment/adapt it, or add
  the dynamic accessor?
- For Entrez, is `PublicationTypeList` worth parsing now or deferred until
  something consumes it?

### Linked artefacts

Tier-1 decisions are settled and promoted:

- [HAS_FEATURE](/design/features/feat-006-classify-records-by-type-with-provenance.md)
- [HAS_PLAN](/design/implementation/plans/plan-002-record-type-publisher-provenance.md)

---

## 4. Embedding-based relevance with a local vector store — `proposed`, effort L

### Context

Relevance is currently page rank or topic-similarity based. The developer's
longer-term direction (raised 2026-09, to be discussed further) is cosine
similarity over LLM tokenisation/vectorisation, using a local database.

### Impact on work happening now

This does not need building yet, but it constrains the present design: the
relevance measure must be a **pluggable scorer** returning named scores, so an
embedding-based scorer can be added alongside page rank and topic similarity
without changing the MCP tool contract or the session model.

### Open questions

- Local model/runtime and where the vector store lives (project, database,
  filesystem), and whether it is a service the MCP server calls.
- Whether embeddings are computed over title+abstract only or include full text
  where available (Zotero full-text index, local PDFs).
- Incremental update strategy as a session grows, and how it interacts with the
  version/delta model for the curated library.

---

## 5. Predictable merging across discovery APIs — `done`, effort M

### Context

The framework's core promise is a single coherent statement per work: the same
paper found via CrossRef, Europe PMC and Entrez should merge into one record.
`Session` already groups records that share an identifier, but `MergedRecord`
resolves conflicting fields with `findAny()` over an `UNORDERED` collector and
concatenates authors, so the result depends on insertion order and duplicated
authors accumulate ([issue-003](/design/implementation/issues/issue-003-merged-record-resolution-is-non-deterministic.md)).

### Problem statement

Merging is neither robust nor predictable, so nothing downstream can rely on it.
Developer decision (2026-09): **fix up front**, independently of the Zotero path,
where duplicates are acceptable because Zotero detects them and offers the user
a UI to resolve them.

### Options

| # | Option | Gives | Effort | Risk |
|---|--------|-------|--------|------|
| A | Documented **per-field priority table** over sources, plus unions for list-valued fields | Predictable and explainable: the rule can be read off a table | M | Medium — needs a policy object and accessor rewrite |
| B | One global source priority order for all fields | Simplest rule | S | Takes abstracts from whichever source ranks first overall, which is often the wrong source per field |
| C | Heuristics (longest abstract, most complete record) | Often "nicer" output | S | Deterministic in letter but unpredictable in spirit — silent magic |
| D | Keep the status quo | Nothing | — | Rejected: the defect |

**Recommendation:** A, with a global fallback order for fields not in the table,
unions for identifiers/authors/licences/citations, retention of every
contributing record so disagreements can be *reported* rather than hidden, and
curated (Zotero) records excluded from merging altogether.

### Decisions taken (developer steer)

- Merging must be **predictable** — the same inputs always produce the same
  output, independent of the order APIs answered.
- It applies to **discovery APIs only**; a curated Zotero item is never merged
  into an API-derived record.
- [feat-006](/design/features/feat-006-classify-records-by-type-with-provenance.md)
  (provenance) is a prerequisite: it identifies curated records and lets a
  conflict be attributed to a source.
- No heuristic comparisons (option C rejected).

### Open questions

- The exact per-field priority order (proposal in
  [plan-003](/design/implementation/plans/plan-003-record-merge-policy.md)).
- Author de-duplication key when ORCID is absent.
- Are licences unioned or priority-picked?
- Should conflicts be surfaced by a tool, or merely retained?

### Linked artefacts

- [HAS_FEATURE](/design/features/feat-007-predictable-record-merging.md)
- [HAS_PLAN](/design/implementation/plans/plan-003-record-merge-policy.md)

---

## 6. Are preprints covered by Europe PMC? — `proposed`, effort S

### Context

The project has a medRxiv client that parses medRxiv's HTML, and Europe PMC indexes
preprints too (results carry `source: PPR`). If Europe PMC covered them completely,
the medRxiv client would be redundant.

### Evidence (sampled 2026-09)

| preprint | found in Europe PMC? |
|---|---|
| medRxiv `10.1101/2021.02.09.21250937` | yes (`PPR285723`) |
| medRxiv `10.1101/2024.01.15.24301296` | no |
| bioRxiv `10.1101/2020.01.09.20017596` | no |

Coverage is partial, and a bare DOI query finds a preprint when Europe PMC has it —
no source filter needed.

### What to decide

Whether to keep the medRxiv client for the coverage it adds, or drop it and accept
Europe PMC's coverage (and its lag). That needs a proper sample rather than three
DOIs, and interacts with the JATS question in
[debt-008](/design/implementation/debt/debt-008-jats-package-is-unused.md): a direct
medRxiv/bioRxiv route is also the only one that yields the preprint's JATS.

### Resolution

Answered by measuring more of the picture: Europe PMC does index preprints
(`source: PPR`) and already supplies their **references and citations** (sample
`PPR285723`: 31 and 10), but medRxiv/bioRxiv's own API is the only source of the
server's metadata (version, license, category, funder), the `jatsxml` full text and
the explicit **preprint→published mapping**. So both are worth having, for
different jobs, and the coverage question is folded into
[HAS_FEATURE](/design/features/feat-009-preprint-clients.md) rather than decided
here.

### Correction (2026-10-17): coverage is not partial

The "coverage is partial" finding above came from three sampled DOIs and is
wrong. Measured properly — Europe PMC `SRC:PPR` hit counts against each
server's own count of distinct preprints — Europe PMC indexes **99-100%** of
both servers, including the current year:

| window | server | Europe PMC | server's new papers | coverage |
|---|---|---:|---:|---|
| 2024 H1 | medRxiv | 6,170 | 6,175 | 99.9% |
| 2024 H1 | bioRxiv | 20,871 | 20,875 | 100.0% |
| 2026 H1 | medRxiv | 7,983 | 8,067 | 99.0% |
| 2026 H1 | bioRxiv | 26,555 | 26,549 | 100.0% |

The earlier sample was misleading because a bare DOI lookup can miss a record
the index does hold.

This matters now rather than academically: bioRxiv has put its HTML search
behind a Cloudflare challenge, so the scraped `MedrxivClient.search` cannot work
at all — see
[issue-015](/design/implementation/issues/issue-015-preprint-html-search-is-blocked-by-cloudflare.md).
Europe PMC is the replacement for preprint *search*. The conclusion above stands
unchanged for everything else: the servers' own JSON API remains the only source
of their native metadata, the JATS full text and the published-version mapping.

---

## 7. (template for further ideas)

Copy this shape for each new idea so the document stays comparable.

### Context

What exists today, with links to the relevant source and design files.

### Problem statement

One or two sentences: what is wrong, missing, or worth changing.

### Options

| # | Option | Gives | Effort | Risk |
|---|--------|-------|--------|------|
| A | … | … | S/M/L | … |

### Recommendation

Which option, and why. What to defer.

### Open questions

- …

---

## Conventions

This project follows continuous evolutionary design (see the
`practice-continuous-evolutionary-design` skill). When an idea here reaches
`accepted` or `in-progress`, create the corresponding design artefacts:

| Artefact | Location |
|----------|----------|
| Feature description | `design/features/feat-NNN-<name>.md` |
| Implementation plan | `design/implementation/plans/plan-NNN-<name>.md` |
| Defect / gap record | `design/implementation/issues/issue-NNN-<name>.md` |
| Technical debt | `design/implementation/debt/debt-NNN-<name>.md` |
| Scope (implemented + planned features) | `design/SCOPE.md` |

Design documents are markdown with frontmatter containing at least
`target-version` and `status`, plus a comma-separated `tags` list. Design
artefacts and code are joined by markdown links whose alt text is the
relationship in upper snake case — a feature links to its plan with
`HAS_PLAN`, a class links to the feature it implements with `IMPLEMENTS`,
and a test-script links to the feature it covers with `HAS_FEATURE`. See the
[design documentation guide](/design/roadmap.md#conventions) for the
relationship set. Link targets are checked by the framework's design-check
script, so an illustrative link written in these documents becomes a real
asserted relationship — describe the format rather than quoting a link when
illustrating it.

## Related documents

- [README.md](/README.md) — supported APIs, build, MCP tool list
- [skills/using-bibliographic-apis/SKILL.md](/skills/using-bibliographic-apis/SKILL.md) — downstream use of the server
- OpenCitations Index v2: <https://api.opencitations.net/index/v2>
- OpenCitations Meta: <https://api.opencitations.net/meta/v1>
