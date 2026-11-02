---
title: Opencitations meta idmap
status: draft
targetVersion: 0.2.0
tags:
- opencitations
- id-mapping
- mcp
HAS_FEATURE:
- feature:feat-004-opencitations-meta-enrichment.md
---

<!-- acts as a description of what will be implemented to allow review and sign-off -->

## Feature

[feat-004-opencitations-meta-enrichment](/design/features/feat-004-opencitations-meta-enrichment.md) —
roadmap option A only: OpenCitations Meta as an **id-mapping source**, not a
record fetcher and not a new analysis capability. Options B–E are out of
scope for this plan (see [roadmap idea 1](/design/roadmap/index.md)).

Design decision on whether this is a separate client or a second operation set
on `OpenCitationsClient` is still open (token-bucket sharing vs separation) —
resolve before implementation.

## Implementation steps:

- [ ] Decide class shape: new `OpenCitationsMetaClient` (separate 180 req/min
      budget) vs Meta operations on `OpenCitationsClient` (shared bucket,
      shared cache manager). Record the outcome in
      [ADR-004](/architecture/decision-records/).
- [ ] Add `metadata(Collection<RecordReference>)` returning
      `Collection<RecordReferenceMapping>`, one mapping per PID pair in each
      entity's `id` field — reusing the existing id-mapper contract so
      `Session.add(RecordReferenceMapping)` and `findAlternateIds()` need no
      change.
- [ ] Give the client the same read-timeout override and token bucket as Index
      v2 (3–6 s responses, 180 req/min).
- [ ] Register `"ocmeta"` in `BiblioFacade.defaultApis()` under `idMap` and
      wire the `idMapApis` branch; make sure a Meta failure cannot break the
      other mappers (it is additive).
- [ ] Add a unit test with a recorded/`@Ignore`d live fixture for the parsing
      (PID string → mappings), plus a recorded live check for the
      omid-only → DOI rescue case.
- [ ] Update [issue-001](/design/implementation/issues/issue-001-non-doi-citation-links-dropped.md)
      and [debt-002](/design/implementation/debt/debt-002-opencitations-queries-dois-only.md)
      with the outcome, and the skill/README tool documentation if a tool is
      added.

## Verification

- Type-level: `mvn -o test-compile`.
- Behaviour: a session built from a DOI whose citations include omid-only
  endpoints shows more citation links after `find_alternate_ids` than before.
- Regression: [test-001](/design/test-scripts/test-001-mcp-protocol-smoke-test.md)
  still passes.


- Prefer which PID when several are present (DOI > PMID > PMCID > OMID)?
- Are `openalex:` and `omid:` mappings useful to the internal model at all, or
  should only DOI/PMID/PMCID pairs be emitted?
