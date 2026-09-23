---
status: draft
target-version: 0.2.0
tags: zotero, repository, workflow, proposed
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Description

An agent can treat the developer's Zotero library as a curated corpus and bridge
it to the working set of records the other APIs discover:

- **read**: query and read Zotero items and collections, exposing them as
  records that merge with PubMed/CrossRef/Europe PMC results, and annotating
  external search results with whether the library already holds them;
- **publish**: create a sub-collection under a seed collection and add selected
  discovered items to it, idempotently, without duplicating what is already
  held;
- **annotate**: add tags to items (preserving existing tags) so the agent can
  record its own decisions — tagging is the agreed mechanism instead of any
  audited selection state on the session;
- **rank**: report relevance as a *labelled set of scores* (page rank, topic
  similarity) for the agent to weigh with the user, never as one opaque number,
  because the measure must stay pluggable for a future embedding-based scorer;
- **maintain**: report (dry run) where existing library items have stale or
  missing metadata or no open-access PDF, using the bibliographic APIs as the
  source of truth, then apply approved field updates and PDFs.

The point of the feature is the two workflows that motivate it: *"here is a
collection of articles I found; find related work and add the most relevant
back into a sub-collection"* and *"refresh my library's metadata and PDFs"*.

Scope boundaries:

- **Local API only** (`localhost:23119`); the Web API is an explicit opt-in and
  is not used, so group libraries are out of scope.
- **Zotero contributes no citation graph** — it is a curated store, not a
  source of citation links.
- A Zotero collection and a `Session` are deliberately **not** the same thing:
  the session is the working set optimised for recall, the collection is the
  curated corpus optimised for precision. Transfer is an explicit promotion of
  a selection, never a wholesale sync.
- Writes are **disabled by default** and, when enabled, restricted to a
  configured target collection. The library is live and syncs to zotero.org.
- **A Zotero item is never overwritten with API-fetched values.** Where the API
  data **agrees** with the item, additional fields may be filled in; where it
  **disagrees**, the API record is imported as a new item and the user resolves
  the duplicate in Zotero. This is the only reliable place to enforce curation: an
  item may be scraped and broken or hand repaired, and the data does not say which
  (`libraryCatalog` names a publisher page for most items; `dateModified` shows
  *that* something was edited, not by whom).
- Enrichment writes **fill gaps rather than overwrite**; replacing `creators` or
  `tags` is a separate, explicit action with a dry-run diff, since both can hold
  the user's own curation.
- **Publication type is not modelled by `Record` today**, which limits ranking,
  publish mapping and enrichment for the ~25% of the library that is not a
  journal article — see [roadmap idea 3](/design/roadmap.md).
- **Matching cannot rely on DOIs.** Only 52.6% of items have one and 30.8% have
  no DOI, ISBN or URL at all, so novelty checks, publish idempotency and
  enrichment all need a title+year (+ first author) fallback key.

Options, trade-offs and the recommended order are recorded in
[roadmap idea 2](/design/roadmap.md). This is a proposal pending sign-off, not
an accepted plan.

## Related records

- [issue-002](/design/implementation/issues/issue-002-third-party-api-endpoint-drift.md) — the local API has its own behaviour differences (no `/items/new`, localised item types, local version namespace)
- [debt-001](/design/implementation/debt/debt-001-session-persistence-uses-java-serialization.md) — a Zotero-backed session was considered and rejected as the fix: the two are different concepts
