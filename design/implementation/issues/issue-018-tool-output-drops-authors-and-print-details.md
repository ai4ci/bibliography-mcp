---
title: Tool output drops authors and print details
status: closed
sinceVersion: 0.0.6
targetVersion: 0.0.11
tags:
- bug
- mcp
- record
TESTED_BY:
- test:java/uk/co/terminological/bibliography/record/TestRecordModel.java
---
## Issue desc

`McpTools.recordJson` decides what an agent sees, and it never emits authors —
even though every client parses them. A record for
`10.1136/bmjqs-2018-008370` is returned as:

```json
{"id": "10.1136/bmjqs-2018-008370", "idType": "DOI",
 "title": "Artificial intelligence, bias and clinical safety",
 "journal": "BMJ Quality & Safety", "date": "2019-01-12",
 "DOI": "10.1136/bmjqs-2018-008370", "publicationType": "journal-article",
 "provenance": {"source": "crossref", "nativeType": "journal-article"}}
```

No `author`, no volume, issue or pages, for a work all of whose sources report
them.

The data is not missing upstream. `Record.getAuthors()` is on the interface
(`record/Record.java:74`), and `CrossRefWork`, `EntrezEntry`,
`EuropePMCCoreResult`, `MedrxivRecord` and `ZoteroItem` all implement it. It is
lost at the JSON boundary: `recordJson`
(`mcp/McpTools.java:209`) copies title, abstract, journal, date, pdf uri, four
identifier types, publication type, publisher and provenance, and stops. Volume,
issue and pages are on `PrintReference`, which a `Record` is not, so they need
`RecordWithCitations`/`PrintRecordWithCitations` to reach the output at all.

Consequence: an agent using this server cannot cite, attribute, or de-duplicate
by author, which is most of what it needs a bibliographic record for. It also
cannot tell two works with the same title apart. The `citation` field that
`Record.render` produces does carry authors, but only once a style is set —
which no tool can do ([issue-019](/design/implementation/issues/issue-019-citation-styles-cannot-be-selected.md)).

Reproduced from a live MCP session
([the original report](/design/implementation/issues/e2e-testing-issues.md));
the JSON above is what that session recorded.

## Suggested fix

Emit authors in `recordJson`, keeping the shape the rest of the JSON uses:

```java
if (!r.getAuthors().isEmpty()) {
    var authors = n.putArray("authors");
    r.getAuthors().forEach(a -> {
        var person = authors.addObject();
        a.getLastName().ifPresent(v -> person.put("lastName", v));
        a.getFirstName().ifPresent(v -> person.put("firstName", v));
    });
}
```

Volume, issue and pages belong in the same object when the record is a printed
one. `TestRecordModel.mcpRecordJsonIncludesTypePublisherAndProvenance` is the
shape to extend.

## Closed

Fixed in 0.0.11. `recordJson` now emits:

- `authors`, as an array of `{lastName, firstName?, initials?, ORCID?}`. A surname
  is the only part every source supplies, so the rest appear only when the source
  gave them rather than as empty strings, and an empty author list is an absent
  field rather than an empty array.
- `volume`, `issue` and `page`, for a record that is a `PrintReference` — the
  sources that carry pagination (Entrez, Europe PMC). A record without them says
  nothing rather than saying they are absent.

Pinned by `mcpRecordJsonIncludesAuthors`,
`mcpRecordJsonIncludesPrintDetailsWhenTheRecordHasThem` and
`mcpRecordJsonOmitsPrintDetailsOfARecordThatHasNone` in `TestRecordModel`, all
written and proven to fail first.

The `citation` field this record mentions is now reachable too — see
[issue-019](issue-019-citation-styles-cannot-be-selected.md).
