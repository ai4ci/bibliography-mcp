---
title: Citation styles are listed but cannot be selected
status: open
sinceVersion: 0.0.6
targetVersion: 0.1.0
tags:
- bug
- mcp
- citation-formatting
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
