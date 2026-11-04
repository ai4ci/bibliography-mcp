---
title: Analysis recomputes topic model per call
status: closed
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- analysis
- performance
IMPACTS: []
---
`Analysis.topicModel` rebuilds the corpus and refits the LDA model on every
call, so an agent exploring topics cannot iterate cheaply (vary topics, alpha,
beta, stopwords) without refitting each time. The R-era analyser held the
result in an object and offered `recalculate(...)`; the plain-Java version
traded that away for statelessness.

Cheap fix when it matters: cache the `TopicModelResult` (and the corpus) on the
`Session`, invalidated by a record-count/version stamp. The existing code
carries a `ponytail:` comment noting this ceiling.


## Closed — 0.2.0 — the ceiling outlived its code

The debt was real and the fix would have been wasted: between this record being
written and this pass over the remaining records, `Analysis` became unreachable
from every entry point. The 0.2.0 surface reduction retired `topic_model` along
with `page_rank`'s home, `Session`, and `Analysis` has no other caller — so an
agent exploring topics cannot iterate cheaply because an agent cannot explore
topics at all.

`Analysis`, `Session`, `BiblioFacade.getSession()` and the legacy scratch test
that was their only exerciser are deleted on `main`; the `nlp-tools`
`master-SNAPSHOT` dependency goes with them. All of it is preserved on the
`feature/topic-modelling` branch, where
[feat-012](/design/features/feat-012-topic-modelling-over-the-repository.md)
records what reinstating the capability would mean — and notes that this
record's question gets sharper there, not softer: against a durable store the
corpus changes as a crawl runs, so a fit keyed by corpus version and parameters
is the shape, not a per-session cache.

The performance ceiling stops existing along with the code that had it.
