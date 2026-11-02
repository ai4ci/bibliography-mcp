---
title: Opencitations index v2
status: final
targetVersion: 0.1.0
---

# Use OpenCitations Index v2, with a longer read timeout

## Context

The client used `https://opencitations.net/index/api/v1/` for citation links.
That host now answers with a permanent redirect (Jersey 1.12 does not follow
redirects), so every call failed, and each failure cost the shared 3-second
read timeout — the symptom was a slow run with zero OpenCitations results.
Two candidate replacements existed: the canonical v1 URL, and the documented
v2 API at `https://api.opencitations.net/index/v2/`.

v1 accepts bare DOIs and returns plain-DOI `citing`/`cited` values, so it
would have worked with a base-URL change alone. v2 requires scheme-prefixed
identifiers (`doi:`, `pmid:`, `omid:`) and returns space-separated PID lists
(`omid:br/… doi:… openalex:…`).

## Decision

Target Index v2. Add the `doi:` prefix when the caller supplies a bare
identifier, parse the PID list to extract the DOI token, give
`OpenCitationsClient` a 30-second read timeout, and set the token bucket to
3/second (180 requests/minute, per the published limit).

## Rationale

* v2 is the version the project documents and maintains; v1 is legacy — and
  this project had already been bitten once by a legacy endpoint disappearing
  (PMC IDConverter, [issue-002](/design/implementation/issues/issue-002-third-party-api-endpoint-drift.md)).
* v2 carries more identifier schemes, which is what the dropped-link problem
  needs ([issue-001](/design/implementation/issues/issue-001-non-doi-citation-links-dropped.md)).
* The parsing changes are small and local to `OpenCitationsCitation`.


* The published rate limit (180/minute) is now respected; the previous
  50/second bucket was ~17× over it.
* The 3–6 s response time is accommodated, so calls take seconds rather than
  failing. Large graph expansions are slow but complete.
* Endpoints with no DOI still cannot be represented in a `CitationLink`, so
  those citations are dropped until
  [feat-004](/design/features/feat-004-opencitations-meta-enrichment.md).
* Only DOI-typed references are queried ([debt-002](/design/implementation/debt/debt-002-opencitations-queries-dois-only.md)),
  despite v2 accepting PMIDs.
