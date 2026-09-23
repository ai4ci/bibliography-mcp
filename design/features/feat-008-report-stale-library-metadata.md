---
status: draft
target-version: 0.2.0
tags: zotero, enrichment, proposed
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Description

Report where the library's metadata has gone stale: for items in a collection,
compare what Zotero holds against what CrossRef, Europe PMC and Unpaywall say, and
report the differences — missing abstracts, absent publishers or identifiers, no
open-access PDF — without changing anything.

This is the "maintain" half of [feat-005](/design/features/feat-005-zotero-curated-repository-bridge.md),
split out so the read/publish workflow could land first. It is read-only by
construction (`roadmap` option E1): the report is what an agent or the user acts
on, and any write it later grows must follow the same rules as the rest of the
bridge — never overwrite an item, and let the agent choose between updating and
duplicating an item whose data disagrees.

## Notes

- Only 52.6% of the library carries a DOI, so a report must be explicit about the
  items it cannot check at all rather than listing them as complete.
- Comparison is normalised-exact (lowercase alphanumerics) per the agreed rule;
  near misses are reported as differences, not silently accepted.
- Attachments and PDF retrieval are a later step again (roadmap E3) and need a
  separate Zotero instance for testing, since file uploads are the least
  reversible write.
