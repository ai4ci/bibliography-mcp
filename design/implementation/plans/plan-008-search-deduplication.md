---
title: Search deduplication
status: final
targetVersion: 0.1.0
tags:
- search
- dedup
- model
HAS_FEATURE:
- feature:feat-001-expose-bibliographic-apis-over-mcp.md
---
## Feature

[feat-001-expose-bibliographic-apis-over-mcp](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md)
(search: "deduplicates by identifier")

Depends on:
- [feat-006](/design/features/feat-006-classify-records-by-type-with-provenance.md) (provenance)
- [feat-007](/design/features/feat-007-predictable-record-merging.md) (merge policy)
- PMC ID Converter and Entrez eLink IdMapper implementations (already present)

## Problem

`BiblioFacade.search()` and `multiSearch()` return a flat list of results from
each API with no deduplication: the same paper found by CrossRef (keyed by
DOI), Entrez (keyed by PMID), and Europe PMC (keyed by PMID or PMCID) appears
three times. The downstream `Session` class *does* merge records sharing any
identifier via `Session.merge()`, but:

1. CrossRef records have `getOtherIdentifiers()` returning `emptyList` — a
   CrossRef record at DOI and an Entrez record at PMID for the same work share
   no common identifier, so they don't merge.
2. `BiblioFacade.search()` bypasses `Session` entirely — tool calls directly
   return duplicates.

The [e2e testing issue](/design/implementation/issues/e2e-testing-issues.md)
explicitly requires: "De-duplicate results across providers by canonical
identifier (DOI, PMID)".

Identifier coverage per API:

| API | Primary ID | Other IDs returned | IdMapper available |
|-----|-----------|-------------------|-------------------|
| CrossRef | DOI | `getOtherIdentifiers()` → empty | No |
| Entrez | PMID | DOI, PMCID | Yes (eLink) |
| Europe PMC (core) | PMID/PMCID | DOI, PMID, PMCID | No |
| Europe PMC (lite) | PMID/PMCID | `getOtherIdentifiers()` via `getDOI()`/`getPMCID()`/`getPMID()` | No |
| Unpaywall | DOI | Empty | No |
| medRxiv | DOI | Other DOIs | No |
| PMC IDConverter | DOI/PMID/PMCID | All three | Yes |

## Solution: two-phase dedup with cross-API rank fusion

After collecting raw results from all search APIs, run the collected
identifiers through the `IdMapper` backends (Entrez eLink, PMC IDConverter —
both Ehcache-backed) to discover cross-scheme equivalences, then merge records
that share any equivalent identifier. The final list is sorted by **reciprocal
rank fusion (RRF)**: a record that appears in more APIs, and at higher
positions, scores higher.

Phase 1 — collect raw results from each API, tracking which API returned each
  record and at what rank
Phase 2 — collect all identifiers from all results
Phase 3 — map identifiers across schemes via IdMapper (cached)
Phase 4 — group by identifier equivalence and merge via `MergedRecord`
Phase 5 — compute RRF score for each merged record and sort descending

### Ranking heuristic: Reciprocal Rank Fusion

```
score(merged) = Σ over each API a of  1 / (K + rank_a(record))
```

Where:
- `rank_a(record)` is the 1-indexed position of the record in API `a`'s
  result list (1 = first, 2 = second, etc.)
- If no record from API `a` contributed to this merged result, the term is 0
- `K` is a smoothing constant (default 5 — tuned for 3 search APIs returning
  ~10–50 results each; standard RRF K=60 would compress all scores into a
  narrow range for our scale)

This captures the user's intent directly:
- A paper returned 1st by all 3 APIs → `3 × 1/(5+1) = 0.50` — ranked first
- A paper returned 1st by 1 API only → `1/(5+1) = 0.17` — lower
- A paper returned 10th by 2 APIs → `2 × 1/(5+10) = 0.13` — lower still
- A paper returned by none of the IdMapper-linked APIs stays at its original
  (single-API) score, so it's not unfairly penalised

### Change overview

```
// Before — no dedup, no ranking
BiblioFacade.search(query)
  → apis each .search(query) → flat concatenated list

// After — two-phase dedup + RRF ranking
BiblioFacade.search(query)
  → apis each .search(query) → with per-(api, rank) tags
  → collect all identifiers
  → map(identifiers) via IdMapper → equivalences
  → group and merge by shared identifier equivalence
  → compute RRF score per group
  → sort by score descending
```

### 1. Rewrite `search()` to collect per-API ranks, dedup, and rank by RRF

Location: `BiblioFacade.java`

A new helper type tracks which API and rank each result came from:

```java
/**
 * A raw search hit tagged with the API that returned it and its position
 * in that API's result list. Used after dedup to compute RRF scores.
 */
private static class ScoredHit {
    final Record record;
    final String api;
    final int rank;  // 1-indexed position in api's results
    ScoredHit(Record record, String api, int rank) {
        this.record = record;
        this.api = api;
        this.rank = rank;
    }
}
```

The rewritten `search()` collects hits with their API/rank tags, deduplicates
via the UnionFind + IdMapper equivalence, then scores and sorts:

```java
/**
 * Search all configured APIs and return deduplicated results ranked by
 * reciprocal rank fusion (RRF) across APIs.
 *
 * @see <a href="https://plg.uwaterloo.ca/~gvcormack/cormack-thesis-2006.pdf">RRF</a>
 */
public Collection<Record> search(Query qry) {
    // Phase 1 — raw results with per-API rank
    List<ScoredHit> scoredHits = new ArrayList<>();
    for (String api : searchApis) {
        List<? extends Record> results = new ArrayList<>(dispatchSearch(api, qry));
        for (int i = 0; i < results.size(); i++) {
            scoredHits.add(new ScoredHit(results.get(i), api, i + 1));
        }
    }
    if (scoredHits.isEmpty()) return List.of();

    // Phase 2 — collect all identifiers
    Set<ImmutableRecordReference> allIds = new HashSet<>();
    for (ScoredHit sh : scoredHits) {
        Record r = sh.record;
        r.getIdentifier().ifPresent(id ->
            allIds.add(recordRef(r)));
        for (RecordReference other : r.getOtherIdentifiers()) {
            other.getIdentifier().ifPresent(id ->
                allIds.add(other.asId()));
        }
    }

    // Phase 3 — map identifiers across schemes
    Set<RecordReferenceMapping> mappings = mappings(allIds);
    UnionFind<ImmutableRecordReference> uf = new UnionFind<>();
    for (ImmutableRecordReference id : allIds) uf.add(id);
    for (RecordReferenceMapping m : mappings) {
        if (!m.isComplete()) continue;
        uf.union(m.getSource().asId(), m.getTarget().asId());
    }

    // Phase 4 — group by equivalence root
    // Each root maps to: (merged record, list of (api, rank) for scoring)
    Map<ImmutableRecordReference, MergedRecord> merged = new LinkedHashMap<>();
    Map<ImmutableRecordReference, List<ScoredHit>> hitsByRoot = new LinkedHashMap<>();
    for (ScoredHit sh : scoredHits) {
        var root = sh.record.getIdentifier()
            .map(Builder::recordReference)
            .flatMap(id -> uf.find(id))
            .orElse(null);
        if (root == null) continue;
        merged.merge(root, MergedRecord.from(sh.record), MergedRecord::merge);
        hitsByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(sh);
    }

    // Phase 5 — rank by RRF
    int K = 5;  // smoothing constant
    List<Map.Entry<ImmutableRecordReference, MergedRecord>> sorted =
        new ArrayList<>(merged.entrySet());
    sorted.sort((a, b) -> {
        double scoreA = rrfScore(hitsByRoot.get(a.getKey()), K);
        double scoreB = rrfScore(hitsByRoot.get(b.getKey()), K);
        return Double.compare(scoreB, scoreA);
    });

    List<Record> out = new ArrayList<>(sorted.size());
    for (var entry : sorted) out.add(entry.getValue());
    return out;
}

private static double rrfScore(List<ScoredHit> hits, int K) {
    return hits.stream()
        .mapToDouble(sh -> 1.0 / (K + sh.rank))
        .sum();
}
```

Note: the `ScoredHit` list is not filtered by the richer-record merge — a
record that appeared in both CrossRef (richer title) and Entrez (poorer)
keeps both ScoredHits for scoring, but only the richest version survives
in the merged output. Since each record from the same equivalence class
contributes the same `record.getIdentifier()` root, no duplication occurs
in the merge step (subsequent calls to `merged.merge(root, ...)` merge into
one `MergedRecord`).

### 2. Add a `UnionFind` utility class

A simple generic union-find (disjoint-set) for identifier equivalence. Minimal
— no path compression beyond what `HashMap` gives.

Location: `src/main/java/uk/co/terminological/bibliography/record/UnionFind.java`

```java
package uk.co.terminological.bibliography.record;

import java.util.*;

/**
 * Tracks equivalence classes of identifiers. Two identifiers are equivalent
 * when an IdMapper found a mapping between them (DOI→PMID, PMID→PMCID, etc.).
 */
public class UnionFind<T> {
    private final Map<T, T> parent = new HashMap<>();

    public void add(T x) { parent.putIfAbsent(x, x); }

    public Optional<T> find(T x) {
        var p = parent.get(x);
        if (p == null) return Optional.empty();
        if (!p.equals(x)) {
            parent.put(x, find(p).orElse(p)); // path compression
        }
        return Optional.ofNullable(parent.get(x));
    }

    public void union(T x, T y) {
        add(x); add(y);
        var rx = find(x).orElse(x);
        var ry = find(y).orElse(y);
        if (!rx.equals(ry)) parent.put(ry, rx);
    }

    /** All identifiers grouped by equivalence-class root. */
    public Map<T, Set<T>> groups() {
        Map<T, Set<T>> out = new LinkedHashMap<>();
        parent.keySet().forEach(k -> {
            var r = find(k).orElse(k);
            out.computeIfAbsent(r, x -> new LinkedHashSet<>()).add(k);
        });
        return out;
    }
}
```

### 3. Replace `search()` and `multiSearch()` call sites

The existing `search()` and `multiSearch()` methods in `BiblioFacade` currently
return flat, undeduplicated results. The MCP tools and `Session` both call
these. Options:

**a) Change `search()` / `multiSearch()` in place** (recommended for ponytail
   — smallest change that achieves the goal)
   - The existing callers (`Session.collectSearch`, `Session.collectSearchByApi`,
     `McpTools` search tool) all benefit automatically.
   - Risk: callers that relied on per-API raw counts will see fewer records.
     This is the *correct* behaviour per feat-001.
   - `Session.collectSearch()` calls `this.facade.search(search)` and then
     calls `this::add` for each hit — the Session will then try to merge
     records that are already merged, which is harmless (idempotent).

**b) Add a new `searchDeduplicated()` method and keep the old `search()`**
   - More code, two paths to maintain.
   - Only choose this if a caller genuinely needs raw per-API results.
   - No such caller exists today per the codebase.

Decision: **option (a)** — replace `search()` and `multiSearch()` in place.

### 4. Extract a `dispatchSearch()` helper

The chain of `if (api.equals("crossref"))` / `else if (api.equals("entrez"))`
appears identically in `GenericSearchBuilder.execute()` and `multiSearch()`.
Move to a private helper so the dedup logic calls it without duplication.

```java
private Collection<? extends Record> dispatchSearch(String api, Query qry) {
    if (api.equals("crossref")) return this.api.getCrossref().search(qry);
    if (api.equals("entrez"))   return this.api.getEntrez().search(qry);
    if (api.equals("europepmc")) return this.api.getEuropePMC().search(qry);
    // no dispatch for medrxiv: Searcher removed
    log.warn("No search available for API: {}", api);
    return List.of();
}
```

### 5. Handle the CrossRef `getOtherIdentifiers()` gap

The `alternative-id` JSON field in CrossRef responses is available but
commented out, and it carries publisher-specific identifiers rather than PMIDs.
However, once the two-phase dedup is in place, CrossRef-only records will be
merged via the IdMapper step — the DOI is mapped to a PMID by the PMC
IDConverter or Entrez eLink, and the PMID bridges to Europe PMC results.

Longer-term, `CrossRefWork.getOtherIdentifiers()` could be wired to expose the
`alternative-id` values (which include publisher IDs, ISSNs, etc.) but this is
not needed for the dedup to work. Tracked as a future improvement.

### 6. New offline tests

Location: `TestSearchDedup.java` (offline, no network)

| Test | What it asserts |
|------|-----------------|
| `identicalDoiMerged` | Two records with same DOI but different titles merge to one |
| `doiPmidEquivalence` | Record A (DOI only) + Record B (PMID only) + mapping between them → one merged record |
| `threeWayMerge` | DOI record + PMID record + PMCID record with full mapping chain → one merged record |
| `noMappingKeepsSeparate` | DOI record with no mapping for a unrelated identifier stays separate |
| `mappingWithNoResults` | Empty IdMapper → no dedup applied (records stay as-is) |
| `unionFindBasic` | Union-find correctly tracks equivalence chains |
| `dispatchSearchRoutesCorrectly` | `dispatchSearch` returns from the right API for each name |
| `rrfScoreInAllApis` | Record returned 1st by all 3 APIs scores higher than record returned 1st by 1 API |
| `rrfScoreHighRankWins` | Same record in 2 APIs: rank 1+10 scores higher than rank 10+10 |
| `mergedRecordKeepsHighestScore` | A record from CrossRef (rank 1) and same record from Entrez (rank 5) → merged with both hit records for scoring |

### 7. Live test

`TestSearchDedupLive` — searches a known multi-API term and asserts the
returned count is less than the sum of per-API counts (i.e., some dedup
happened). Not a strict equality test (API results change over time).

## Edge cases and notes

- **ScienceDirect-healthy volunteer**: some DOIs (like `10.1016/...`) have
  neither PMID nor PMCID and won't be mapped. They stay as single records —
  correct, since no other API had anything to contribute. Also get a lower RRF
  score (appear in 1 API only), which is correct — less corroborated.
- **medRxiv preprints**: have only a DOI and no PubMed presence. The IdMapper
  will find no mapping, and they stay as single records — correct. They'll
  rank lower than cross-corroborated results, which matches their weaker
  corroboration.
- **CrossRef-only records with no PMID/PMCID**: no mapping found → stay as
  single records with a single-API RRF score. If every other API also returned
  them (unlikely since they have no PMID), they'd still rank well.
- **Europe PMC lite results**: have `getDOI()`, `getPMID()`, `getPMCID()`
  accessors but `getOtherIdentifiers()` is not overridden (inherits empty from
  `Record`). If lite results are used in search (currently the default for
  Europe PMC search), they need `getOtherIdentifiers()` to expose these for the
  identifier collection phase (Phase 2). Check the search return type:
  `EuropePMCSearchResult` (or whatever the search returns) must override
  `getOtherIdentifiers()`.
- **Ehcache persistence**: IdMapper results are cached by the existing
  `CachingApiClient` infrastructure (disk-backed). Repeated searches for the
  same identifiers cost nothing after the first lookup.
- **RRF K tuning**: default K=5 is chosen for 3 APIs × ~10–50 results each.
  If more search APIs are added, K may need to increase to prevent a single
  API from dominating. If result counts grow much larger (>100), K=60 (standard
  IR RRF) may be more appropriate.
- **MergedRecord vs findAny**: `MergedRecord.merge()` already uses
  `MergePolicy` with per-field priority — deterministic.


- [ ] `mvn -o test -Dtest=TestSearchDedup` — all offline tests pass
- [ ] `mvn -o test-compile` — no new compilation errors
- [x] `./scripts/run-live-tests.sh -Dtest=TestSearchDedupLive` — dedup confirmed
- [ ] `loom validate` → ok: true
- [ ] MCP smoke test: `search` returns fewer records when more APIs are enabled
- [ ] `collect_search` still populates session correctly

## Status: implemented, and now actually asserted — 0.1.0

The code this plan describes was already in `BiblioFacade` — `ScoredHit`,
`dedupedSearch`, `UnionFind`, `dispatchSearch`, `rrfScore`, all as written here,
with `search()` and `multiSearch()` replaced in place (option (a)). What was
missing was the evidence: of the ten tests in the table above, only the
`UnionFind` and `rrfScore` ones existed. The dedup itself — the thing the plan is
about — had no test at all, and the live test had never run.

Added, written from this plan's intent rather than from the code, and all passing:

| test | asserts |
|---|---|
| `identicalDoiMerged` | one paper found by two APIs under one DOI is one record |
| `doiPmidEquivalence` | a DOI record and a PMID record join through a mapping neither record carries |
| `threeWayMerge` | DOI → PMID → PMCID chains into one group |
| `noMappingKeepsSeparate` | a mapping between other papers joins nothing |
| `mappingWithNoResults` | no mappings means no merging |
| `corroboratedResultsRankAboveSingleApiOnes` | two APIs at ranks 2 and 5 beat one API at rank 1 |

They drive `dedupedSearch` directly, with the identifier mappings supplied by a
`BiblioFacade` subclass that overrides `mappings()`, so what is asserted is the
grouping and not the network. `dedupedSearch` is package-visible for that reason.

### Two things this plan worried about, resolved

- **Europe PMC lite results** ("check the search return type"): `search(Query)`
  returns `EuropePMCCoreResult`, which does override `getOtherIdentifiers()` with
  its DOI, PMID and PMCID. The lite path is not used by search, so the gap the plan
  anticipated is not there.
- **`TestSearchDedupLive` read `inst/secrets.properties`**, a path that exists
  nowhere in this repository, so the class threw before reaching an assertion and
  had never run despite being ticked on the checklist. It now reads the path the
  other live tests use, and carries the assertion this plan asked for: the
  deduplicated total against the sum of the per-API totals.

### K, with four search APIs

`K = 5` was chosen here for three search APIs. OpenAlex makes four
([plan-013](plan-013-openalex-client.md)), which this plan anticipated:
"if more search APIs are added, K may need to increase". It has **not** been
re-tuned or measured. Four is still far from the scale where the standard K = 60
applies, and a corroborated paper still outranks a single-API one, so this is a
question rather than a defect.

### The live test, rescoped — and what it measures

`TestSearchDedupLive` searched "SARS-CoV-2" with no limit and took minutes:
hundreds of records per run, through an identifier-mapping phase that scales with
them. It is now one author in one year (`Leon Danon`, 2018), which every API can
answer in seconds and which they genuinely overlap on, and the cache is left on as
in the other live tests. **9.5 s for the class, against roughly ten minutes.**

What it reports, live:

| API | records for that query |
|---|---|
| crossref | 20 — a fuzzy author match, filtered on deposit date by design (issue-008), and capped at Crossref's own default page |
| entrez | 2 |
| europepmc | 2 |
| openalex | 4 |
| **deduplicated** | **24, against 28 one API at a time** |

Two things worth knowing about those numbers:

- **Crossref dominates and is the least precise.** Its author search matches
  loosely and its date filter is the deposit date, so it contributes 20 of the 28.
  In one run it returned 0 instead of 20 — a transient failure that
  `CachingApiClient` turns into an empty result (issue-013), which is why the
  assertion is an inequality rather than a count.
- **Only four of the 28 merged.** Little overlap is a fact about these APIs for
  this author rather than a dedup failure, and the test says so in its output
  rather than failing.
