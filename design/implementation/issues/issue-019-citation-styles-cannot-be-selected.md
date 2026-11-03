---
title: Citation styles are listed but cannot be selected
status: closed
sinceVersion: 0.0.6
targetVersion: 0.1.0
tags:
- bug
- mcp
- citation-formatting
TESTED_BY:
- test:java/uk/co/terminological/bibliography/record/TestRecordModel.java
---
## Issue desc

The `citation_styles` tool lists ~9,500 CSL style names and describes itself as
styles "for rendering references". Nothing can render a reference with one.

The rendering exists. `Record.render(style)`
(`record/Record.java:94`) calls `CiteProcProvider.convert` and returns the
formatted string, and `recordJson` will attach it as a `citation` field — but
only `if (f != null && citationStyle != null)` (`mcp/McpTools.java:250`), and
`citationStyle` is a static field set only by
`McpTools.withCitationStyle(String)` (`mcp/McpTools.java:170`), which is
`@Deprecated` and has no caller anywhere in `src/main`. So from an MCP client
the condition is unreachable: `citation` is never in the output.

An agent therefore sees a list of 9,500 styles it cannot use, and the tool
description promises a capability the server does not expose. The original report
guessed a missing `biblio_render_citations` tool; the gap is smaller than that —
the renderer is there and is not wired to anything.

Reported from a live MCP session
([the original report](/design/implementation/issues/e2e-testing-issues.md)).

## Options

| Option | Gives | Cost |
|--------|-------|------|
| A | `citation_style` argument on the record-returning tools | touches every tool's schema |
| B | one `citation_style` tool that sets the session style, as the original report suggested | one tool, but the state is a mutable static |
| C | delete `citation_styles` and stop claiming the capability | honest, loses real functionality |

## Suggested fix

B, because the style is naturally session-wide and the tools that return records
should not each need a formatting argument: add a `citation_style` tool that
validates against `getSupportedCitationStyles()` and sets the style, then delete
`withCitationStyle` once it has a caller. The static field is the wrong home for
this — `Session` is — but that is
[debt-001](/design/implementation/debt/debt-001-session-persistence-uses-java-serialization.md)
territory, and a working tool beats waiting for it.

## Closed

Fixed in 0.1.0 as option B. A `citation_style` tool takes a style name,
validates it against `getSupportedCitationStyles()` — the same list
`citation_styles` advertises — and sets it for the server; every record returned
afterwards carries a `citation` field. Passing no style switches rendering off
again.

Two details worth keeping:

- **An unknown style is refused, not stored.** `Record.render` returns an empty
  `Optional` for a style it cannot load, so storing a bad name would silently drop
  the citation field from every record afterwards and look exactly like the bug
  this issue describes.
- **The `f != null` guard in `recordJson` is gone.** Rendering is the record's own
  job and never consulted the facade, so the guard only meant that a caller
  passing no facade got no citation however the style was set.

`withCitationStyle` is kept rather than deleted: it is now the setter the tool
calls. Its proper home is still `Session` rather than a static field — that waits
on [debt-001](../debt/debt-001-session-persistence-uses-java-serialization.md).

Pinned by `TestCitationStyleTool`, written and proven to fail first.

### A caveat, found while verifying this through the protocol — and since fixed

Selecting a style worked immediately; **rendering did not, in the shaded jar**.
The tool set the style, the server accepted it, and no record carried a
`citation` field, because `Record.render` failed inside the shaded jar for a
packaging reason with nothing to do with this issue — and failed silently. That
was written up as
[issue-022](issue-022-shaded-jar-cannot-render-citations.md) and fixed in the
same release by dropping the shading.

The end-to-end result, over the stdio protocol against the built jar:

```
citation_style {"style":"ieee"}  ->  {"style":"ieee"}
fetch 10.1136/bmjqs-2018-008370  ->  "citation": "[1]R. Challen, “Artificial
    intelligence, bias and clinical safety.,” BMJ Qual Saf, Jan. 2019."
```

The silence that hid it is recorded separately as
[debt-013](../debt/debt-013-record-render-swallows-its-failures.md).
