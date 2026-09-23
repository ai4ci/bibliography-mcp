---
status: open
since-version: 0.1.0
target-version: 0.2.0
tags: maintenance, api-drift
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Issue desc

Bibliographic APIs move without notice, and the failure mode is quiet:
results go empty for one API while the rest still work, or a parse warning
appears in stderr. Three instances found in one session:

| API | Drift | Symptom | Fix applied |
|-----|-------|---------|-------------|
| PMC IDConverter | endpoint moved to `pmc.ncbi.nlm.nih.gov/tools/idconv/api/v1/articles/`; `request` became an object, `response-date` hyphenated, numeric `pmid`, new `requested-id` | `Could not parse API result … Unrecognized field` | endpoint updated, `@JsonIgnoreProperties(ignoreUnknown = true)` on result types |
| OpenCitations Index | `opencitations.net/index/api/v1` 301s; v2 at `api.opencitations.net/index/v2` with scheme-prefixed ids and PID-list values | empty results after ~3s `Read timed out` | [ADR-003](/architecture/decision-records/adr-003-opencitations-index-v2.md) |
| Entrez eLink | returns `Length Required` for some `dbfrom`/`linkname` combinations | zero links from entrez for those queries | not investigated |

The general lesson: treat a single-API empty result as suspicious rather than
authoritative, and check the API's docs before assuming the query is wrong.

No automated detection exists yet — a periodic live-API check would turn this
from reactive into proactive (see [roadmap](/design/roadmap.md)).

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/pubmedclient/TestApiDriftParsing.java)
