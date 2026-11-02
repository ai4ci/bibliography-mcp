# Standards

Conventions observed in this codebase. Some are enforced by tooling, most rely
on review.

* **Indentation**: tabs (the Eclipse formatter settings in `.settings/` reflect
  this).
* **Java version**: 11 — `var` is used freely, records/sealed types are not.
* **Null safety in client APIs**: methods return `Optional` rather than
  throwing; bulk operations rely on Java 8 streams and incomplete results are
  filtered rather than reported. Prefer extending that pattern to new clients.
* **Fluent construction**: query builders (`OpenCitationsClient.QueryBuilder`)
  and configurators (`BiblioFacade.with…`) return `this`.
* **Logging**: SLF4J `Logger` per class; parameterised messages
  (`log.info("found {} hits", n)`), never string concatenation.
* **No dead code**: remove methods that a change made unnecessary (the
  framework's rule — do not delete code that was already dead unless asked).
* **Design links**: design documents point at the code that implements or
  proves them from their frontmatter (`IMPLEMENTED_BY`, `TESTED_BY`, `IMPACTS`
  — see `design/document-model.mmd`). Java is not one of the languages loom can
  annotate, so there is no matching comment syntax in the source and the
  frontmatter is the only copy of the link.
* **Commits**: lowercase imperative subject, no type prefix, no trailing
  period.
* **Comments**: explain why, and mark deliberate simplifications with a
  `ponytail:` comment naming the ceiling and the upgrade path.
* **Secrets**: never committed; passed at runtime
  ([interface-002](/design/external-interfaces/interface-002-configuration-and-secrets.md)).

## Design documents

`design/` is a loom graph: every document carries YAML frontmatter whose class
comes from its folder, and `design/document-model.mmd` declares the classes,
attributes and relations. A new document is a file with frontmatter in the right
folder — the number is naming, not identity, so gaps after deletion are fine.

* `status` and `targetVersion` are required on every document; `unassigned` is
  the honest answer until the [roadmap](/design/roadmap/index.md) says otherwise.
* Relations are written once, in the document that owns them; loom derives both
  directions.
* `loom validate` gates the tree, `loom render` fills the tables in
  [design/INDEX.md](/design/INDEX.md), `loom index` fills its feature list. Run
  the two that write anything before committing a document change.
* An idea that is not yet a feature, plan, issue or debt record belongs in the
  roadmap, not in a new file in `design/`.
