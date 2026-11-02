---
title: Analysis recomputes topic model per call
status: open
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- analysis
- performance
IMPACTS:
- main:java/uk/co/terminological/bibliography/mcp/Analysis.java
---

`Analysis.topicModel` rebuilds the corpus and refits the LDA model on every
call, so an agent exploring topics cannot iterate cheaply (vary topics, alpha,
beta, stopwords) without refitting each time. The R-era analyser held the
result in an object and offered `recalculate(...)`; the plain-Java version
traded that away for statelessness.

Cheap fix when it matters: cache the `TopicModelResult` (and the corpus) on the
`Session`, invalidated by a record-count/version stamp. The existing code
carries a `ponytail:` comment noting this ceiling.

