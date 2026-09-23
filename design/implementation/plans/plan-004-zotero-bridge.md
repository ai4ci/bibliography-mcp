---
status: final
target-version: 0.2.0
tags: zotero, mcp, repository
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

<!-- acts as a description of what will be implemented to allow review and sign-off -->

## Feature

[HAS_FEATURE](/design/features/feat-005-zotero-curated-repository-bridge.md)
Design context: [roadmap idea 2](/design/roadmap.md).

## Approach

- **Local API only** (`http://localhost:23119/api`), fail fast with a clear message
  if it is unreachable or the preference is off (403). No web API, no group
  libraries.
- **No EHCache for library data.** Library contents are mutable and versioned;
  the Zotero client does its own reads and keeps a small in-memory index rather
  than inheriting `CachingApiClient` (see plan-003 for why caching is dangerous
  here).
- **Never overwrite a Zotero item.** Writes are additions: new sub-collection,
  new items, tag union. Writes are disabled unless configured, and restricted to
  a configured target collection (`api-test` today).
- **Testable seam**: all item→record mapping and all matching rules are pure
  functions over JSON, unit-tested offline; the HTTP layer is thin and verified
  live.

## Implementation steps:

- [x] `ZoteroItem implements Record` wrapping an item's `data` node: type via
      `PublicationTypes.fromZotero`, container from `publicationTitle` /
      `bookTitle` / `proceedingsTitle`, publisher from `publisher` / `institution`,
      DOI + ISBN identifiers, creators (prefer `creatorType: author`, fall back to
      all), tolerant free-text date parsing, provenance `source=zotero`,
      `nativeType=<itemType>`, `url=zotero:<serverId>/items/<key>`.
- [x] `ZoteroLibraryIndex`: normalised match keys (DOI, ISBN, lowercase-alphanumeric
      title + year) with a `findKey(Record)` lookup, built from one unpaginated read.
- [x] `ZoteroClient`: base URL, `Zotero-API-Version`, `Zotero-Server-ID` capture,
      `GET /items/top`, `/collections`, collection items, `q` search; reachability
      check; batching for writes. (`since=` deltas and child attachments are not
      done: the index is rebuilt per use.)
- [x] `ZoteroConfig` from the properties file: `zotero-mode` (`local`/`off`),
      `zotero-allow-writes` (default false), `zotero-write-collection`,
      `zotero-user-id` (default 0).
- [x] MCP tools: `zotero_status`, `zotero_collections`, `zotero_import`
      (collection/ids → session), `zotero_check` (novelty against the library),
      `zotero_publish` (sub-collection + add missing items), `zotero_tag`
      (union tags, version-preconditioned, retry once on 412).
- [ ] `zotero_enrich_report` (dry-run gaps report, no writes) — moved to
      [feat-008](/design/features/feat-008-report-stale-library-metadata.md).
- [x] Batch the tagging: tags are applied to up to 50 items in **one** request,
      because a Zotero write authorisation is usually single-use and each request is
      a permission prompt (Zotero allows five prompts a minute).
- [x] Record `remember` from the authorisation response and report it as
      `writeAuthorisation` in `zotero_status`, so an agent can suggest "Always Allow"
      when a session will need several writes.
- [x] Write authorisation: on `401` call `POST /local/authorize` with
      `Zotero-Server-ID` and the app name (the user sees a dialog), retry with the
      returned key; surface `403 denied` and `429` (dialog spam limit) clearly.
- [x] Design links + docs: config keys in interface-002, `test-003` test-script,
      `feat-005` status. (Skill and README tool lists updated too.)

## Tests

- [x] `TestZoteroMapping`, `TestZoteroLibraryIndex`, `TestZoteroItemWriter`
      (offline, sanitised fixtures modelled on the real payload shapes): mapping per
      item type, matching with and without identifiers, the new-item JSON, and the
      guarantee that a `document` never becomes an `attachment`.
- [x] Live verification (read): imported a real 48-item collection; all 48 were
      recognised as already held.
- [x] Live verification of the batched tag write: **two items tagged in one call, with
      one authorisation** (`taggedCount: 2`, real keys returned, existing tags kept),
      and `zotero_status` reporting `writeAuthorisation=remembered` afterwards. A
      second run was granted its key immediately, with no further dialog.
- [x] Live verification (write): an all-held publish created nothing and left no
      empty folder; a second created exactly the 2 requested items in a new
      sub-collection of `api-test`, tagged, reporting 48 held and 92 left for a
      next call, with no refusals.

## Verification

- [x] `mvn -o test` (52 tests), `mvn -o test-compile`
- [x] `./scripts/mcp-smoke-test.sh` (27 tools listed, offline round trips)
- [x] `Rscript …/design-check.R -d .` → 0 defects

## Open questions

- Date precision: a year-only Zotero date cannot be a `LocalDate`; plan is to map
  `YYYY` → Jan 1 and `YYYY-MM` → the 1st, documented as lossy, rather than dropping
  the date and losing year matching.
- Should Zotero also become a general `search` source in `BiblioFacade` (so
  existing tools see the library), or stay behind Zotero-specific tools? Plan is
  the latter for now — it avoids changing the meaning of existing tools.
- Attachment/PDF location requires child-item lookups; deferred.
