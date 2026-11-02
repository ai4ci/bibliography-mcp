---
title: Clean up collection metadata
status: final
targetVersion: 0.3.0
tags:
- zotero
- enrichment
- metadata
HAS_PLAN:
- plan:plan-005-metadata-cleanup.md
HAS_TEST:
- testscript:test-004-metadata-cleanup-tests.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/api/crossref/CrossRefWork.java
- main:java/uk/co/terminological/bibliography/mcp/McpTools.java
- main:java/uk/co/terminological/bibliography/record/PlainText.java
- main:java/uk/co/terminological/bibliography/api/zotero/MetadataComparison.java
- main:java/uk/co/terminological/bibliography/api/zotero/ZoteroClient.java
- main:java/uk/co/terminological/bibliography/api/zotero/ZoteroItemWriter.java
---

## Description

The user opens **a collection** and asks the agent to clean up its metadata. The
agent reads the collection, fetches the corresponding records from the
bibliographic APIs, compares them item by item, and reports what would change:
straightforward gaps it can fill, and conflicts it cannot decide. Conflicts go to
the user; straightforward merges do not need to be asked about individually. Once
the changes are agreed the agent **writes them back, overwriting the existing
Zotero items**.

Deliberately **collection-scoped**, not library-scoped: a collection is a unit of
work someone can review, and 2060 items is not a diff anybody reads.

## The division of labour

This is the part that matters most, because the temptation is to build a smart
merger:

- **the tool compares and reports.** Field by field, in the open: what the Zotero
  item holds, what each API says, whether they agree, and where each API value came
  from. No judgement, no heuristics, no "best" value.
- **the agent merges and decides.** Which of `2019` and `2019-01-01` to keep,
  whether a publisher rename is an improvement, whether a conflict needs the user
  at all — these are judgement calls, and they belong to the agent in conversation
  with the user, not to a rule buried in the library.
- **the tool applies what it is told.** An explicit set of field changes per item,
  nothing inferred.

So matching and merging stay simple in `bibliographic-api`; the information is
surfaced for the agent to act on.

## What a comparison reports

Per field: `agree` (equal after normalisation), `fillable` (Zotero empty, an API
has a value), `conflict` (both present and different), or `zotero-only` (the APIs
have nothing). Per item, a summary classification, plus `no identifier` and
`no API record` as first-class outcomes rather than silence — 52.6% of the library
carries a DOI and 30.8% has no identifier at all, so a report that cannot say "I
could not check this one" is misleading.

Comparison is normalised-exact — lowercase alphanumerics — so punctuation and case
are not conflicts. Anything that differs after that is a conflict for the agent to
judge rather than a near-miss to be silently accepted, and dates are no exception:
`2019` against `2019-01-01` is reported as a conflict with both values shown, so the
agent can keep the year or take the more precise date. No field gets a special rule
that decides for it.

## Write rules

This feature changes the earlier "a Zotero item is never overwritten"
rule into what the user actually needs, which is the distinction below.

- **Overwriting an existing item is supported and is the point of this feature.**
  It is an explicit action by the agent, on named items, with named fields.
- **Never silently.** The agent is expected to have surfaced conflicts to the user
  first; the tool reports what it changed, including the previous values, so the
  change is reviewable afterwards.
- **Update or duplicate is the agent's call**, depending on the task: clean-up of
  metadata means updating the item; importing the results of a snowball search
  means a duplicate is usually better, because the user can resolve it in Zotero's
  duplicate view. The tool offers both — `zotero_apply_updates` for the update, and
  `zotero_publish` with `includeHeld` for the duplicate.
- **Unrelated fields are untouched.** A multi-object write follows PATCH semantics,
  so only the named fields change; tags, collections, relations and attachments are
  not disturbed.
- The existing gates stand: writes off by default, confined to the configured
  collection, and one item's version as a precondition so a concurrent edit is
  reported rather than clobbered.

## Tools

| tool | what it does |
|------|--------------|
| `zotero_cleanup_report` | compare every item in a collection against the APIs and report agreements, fillable gaps, conflicts and uncheckable items |
| `zotero_apply_updates` | apply an explicit `{itemKey, fields}` change set to existing items, in one request, with previous values reported back |
| `zotero_publish` (existing, gains `includeHeld`) | create new items; with `includeHeld` it will create one the library already holds, which is the duplicate route |

## Implementation

Both tools are implemented and were exercised live against a real collection: the
report matched titles, dates, abstracts, publishers, containers, DOIs, URLs and
creators against CrossRef, Europe PMC and Unpaywall, attributing each value to the
source that asserted it and cleaning markup out of the values it offers; an
explicit update changed exactly the two fields named, reported what they had been
before, and left the item's tags and title untouched.

Markup is handled at the root rather than where it was noticed: `PlainText`
reduces a fragment to text (tags stripped, a JATS heading dropped with its content,
entities unescaped, whitespace collapsed) and is applied when a record is **mapped**
(Crossref returns abstracts as JATS XML), again in the writer as a last gate, and in
the comparison. Because cleaning both sides would make a marked-up abstract look
like an agreement, the report **flags** a Zotero value that holds markup
(`zoteroHasMarkup`) and offers the cleaned value, so markup already in the library
is findable and fixable with the same two tools. Measured before the fix: 86 of
2110 items held markup, 75 from the user's own imports and 11 from the bridge's
first publish.

Three defects the live run exposed, all fixed: CrossRef abstracts arrived as JATS
XML (a markup-wrapped abstract read as a conflict with itself, and could have been
written into `abstractNote` verbatim); the container was compared against a
non-existent `container` field, so it was always reported as fillable and an update
using that name would have been refused; and Unpaywall reports every author as
"Unknown", which made every item it appeared on look like an author conflict.


- Attachments and PDF retrieval (roadmap E3): file uploads are the least reversible
  write and need a separate Zotero instance to test against.
- Editing tags as part of clean-up: `zotero_tag` already unions tags, and a tag
  diff is a separate question.
- Any automatic approval inside the tool. "Straightforward merges are approved
  automatically" is a *workflow* decision the agent makes with the user, not a
  policy the tool applies on its own.
