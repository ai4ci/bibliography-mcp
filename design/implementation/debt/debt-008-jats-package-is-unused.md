---
title: Jats package is unused
status: closed
sinceVersion: 0.1.0
targetVersion: 0.4.0
tags:
- dead-code
- jats
IMPACTS:
- main:java/uk/co/terminological/bibliography/jats/JatsEntry.java
---

## Resolution

Closed 2026-09-28: the package is now used. `MedrxivClient` fetches a preprint's JATS
full text and turns it into a `JatsEntry`, whose `getCitations()` supplies the
reference list — the only route to the references of a preprint that Europe PMC does
not index. Two defects in it were fixed in the process (`getTitle()` scoped to the
front matter, and the dropped citation index in `ImmutableCitationLink`).


The `uk.co.terminological.bibliography.jats` package (`JatsEntry`, `JatsAuthor`,
`JatsReference`, `JatsCitedAuthor`) is imported by nothing outside itself, and the
`src/test/resources/medrxivJats.xml` fixture is read by no test. The medRxiv client
parses medRxiv's **HTML** (`span.nlm-given-names`) through fluentxml instead.

Planned use (2026-09): [feat-009](/design/features/feat-009-preprint-clients.md)
option B would parse medRxiv's `jatsxml` full text for its reference list — 28
`<ref>` in one sample — which is the only way to get references for a preprint that
Europe PMC does not index. That is the job this package was written for.

Left in place deliberately (developer decision, 2026-09): it parses a whole JATS
*document* into a `Record` via XPath, which is a different job from the markup that
actually causes trouble here — JATS *fragments* inside JSON fields (Crossref
abstracts), which `PlainText` reduces to text. If the bridge ever ingests
bioRxiv/medRxiv JATS documents properly, this is the starting point; otherwise it
should be deleted.

