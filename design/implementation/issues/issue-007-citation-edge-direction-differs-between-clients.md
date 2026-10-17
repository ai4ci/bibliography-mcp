---
status: closed
since-version: 0.1.0
target-version: 0.3.0
tags: citations, graph, data-integrity, entrez, europepmc
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Issue desc

`CitationLink` has a source and a target, but the clients do not agree on which
end is which. Three different conventions are in use, so the directed citation
graph `Session` builds contains edges pointing both ways and nothing downstream
can rely on the direction.

Found while writing package documentation for `..bibliography.record` and
`..bibliography.client`; not introduced by the recent refactor.

| Client | `citesReferences(X)` | `referencesCiting(X)` |
|---|---|---|
| CrossRef (`CrossRefWork.getCitations`) | source = X (citing) | — |
| medRxiv / JATS (`JatsEntry.getCitations`) | source = X (citing) | — |
| OpenCitations (`OpenCitationsCitation`) | source = citing | source = citing |
| **Europe PMC** | source = X (citing) | **source = X (cited)** |
| **Entrez** | **source = reference (cited)** | **source = X (cited)** |

So:

- **CrossRef, medRxiv and OpenCitations** agree: the source cites the target.
- **Europe PMC** makes whichever work was asked about the source, which happens
  to be right for `citesReferences` and backwards for `referencesCiting`
  (`EuropePMCClient.java:459`, `:470` vs `:513`).
- **Entrez** is uniformly the other way round: the cited work is the source in
  both directions. The flip is in `EntrezLinks.getCitations(boolean invert)`,
  which returns `citationLink(rhs, lhs)` for the un-inverted case even though
  `lhs` is the work the eLink query started from (`EntrezLinks.java:117-130`,
  called with `false` from `EntrezClient.citesReferences` and `true` from
  `EntrezClient.referencesCiting`).

## Why it matters

`Session.add(CitationLink)` adds a directed edge `source -> target`
(`Session.java:101`), and `Analysis.pageRank` runs over that graph. PageRank
scores a node by its *incoming* edges, so with the source-cites-target
convention a well-cited paper scores highly — which is the intent. Edges
arriving from Entrez, and from Europe PMC's "cited by" direction, point the
opposite way, so they push rank in the wrong direction and
`expand_citation_graph` up/down traverses them backwards.

The defect is invisible in single-source use and in the offline tests, because
each client's own fixtures are self-consistent. It only shows up once a session
mixes citation sources, which is the default (`BiblioFacade.defaultApis()`
routes `cites` to five APIs and `citedBy` to three).

## Resolution

Fixed 2026-10-17 (developer). The convention is **source cites target**, now
documented on `CitationLink`, and the two divergent clients were brought into
line:

- `EntrezLinks.getCitations(boolean invert)` — the two branches were swapped, so
  `citesReferences` (`invert=false`) now yields `citationLink(from, to)` with the
  queried work as the citing source, and `referencesCiting` (`invert=true`)
  yields `citationLink(to, from)` with the citing work as the source.
- `EuropePMCClient.referencesCiting` — now builds `citationLink(target, source)`
  where the loop variable holding the citing article becomes the link source.
  `citesReferences` was already correct and was left behaving the same way; both
  methods were renamed internally so the roles read off the code.

Still outstanding, and the reason this could drift again: **there is no test
asserting edge direction for any client.** The extraction tests assert that
links are produced, not which way they point, which is why the defect survived
this long. A fixture-based direction test per citation client is worth adding.

## Suggested fix

Settle the convention explicitly as **source cites target** — the one the
majority already use, and the one that makes PageRank mean what it is being
asked to mean — then:

- document it on `CitationLink`, since the type names alone do not imply a
  direction;
- fix `EntrezLinks.getCitations` so the citing work is always the source, and
  check the `invert` flag's two call sites still mean what their names say
  (it may be clearer to drop the flag and pass the direction explicitly);
- fix `EuropePMCClient.referencesCiting` to build `citationLink(r2, r, i)`;
- add a test per citation client asserting direction against a recorded fixture
  whose citing/cited roles are known, so this cannot drift again. The existing
  extraction tests assert that links are produced, not which way they point.

Worth considering at the same time: `CitationLink.getIndex()` is the position in
a reference list, which is meaningful only in the `citesReferences` direction.
