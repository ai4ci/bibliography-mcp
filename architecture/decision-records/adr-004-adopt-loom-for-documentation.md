---
title: Adopt loom for design documentation
status: final
targetVersion: 0.1.0
tags:
- documentation
- tooling
---

# Document the design with loom, not with markdown conventions

## Context

`design/` held 60 documents whose structure was a convention: a feature linked
to its plan with `[HAS_PLAN](/design/implementation/plans/plan-001.md)`, a plan
linked back with `HAS_FEATURE`, an issue linked to the tests that prove it with
`TESTED_BY`. Nothing enforced the alt text was a real relation name, that the
target existed, or that a `status` key was spelled the same way twice. A link
written to illustrate a sentence became indistinguishable from a link asserting
a fact.

The framework's checker was an R script outside the repository. It was not run
by the build, so link rot accumulated through the refactor silently: after
`c44fd7a` around 40 recorded source paths pointed at files that no longer
existed, and a document could claim a feature was implemented by a class that
had been deleted.

## Decision

`design/` is a loom graph. `design/document-model.mmd` declares the document
kinds, their attributes and the relations between them in one mermaid
`classDiagram`; the folder a file sits in decides its class, and YAML
frontmatter holds its attributes and relations. `loom validate` gates the tree
and `loom render` fills the tables in `design/INDEX.md`.

Relations live in frontmatter, as lists of uris (`plan:plan-003-record-merge-policy.md`),
not in prose. A document says once, in the class that owns it, that a plan
follows from a feature; loom derives the other direction, so the back-links that
used to be maintained by hand cannot disagree with the forward ones.

## Consequences

* **A convention becomes a check.** `status` and `targetVersion` are required,
  so a new document without them fails validation instead of quietly missing
  from the rendered views. A relation to a deleted file fails validation.
  Source files are checked the same way: a `TESTED_BY` naming a test class that
  has moved is an error, which is the check the corpus needed and never had.
* **Prose loses the relation syntax.** Moving the links out of the body means a
  sentence can no longer carry a relationship in its link text. Where a link was
  woven into a sentence the migration kept the link and dropped the capitals, so
  the explanation stays and the assertion moves to the frontmatter. The
  trade-off is real: the reason *this* plan serves *that* feature is now further
  from the claim itself.
* **Java cannot be annotated back.** Loom writes reverse links for R,
  TypeScript and Python only. Production code cannot point at the feature it
  implements, so the frontmatter is the only copy of the link and the direction
  is always design → code. The previous convention (`IMPLEMENTS` comments in
  Java) is dropped rather than emulated.
* **Class names are camelCase**, and so are the frontmatter keys they describe,
  because the mermaid grammar rejects `-` in a class, attribute or relation name
  and `-` is illegal in a loom uri scheme. Directories keep their hyphens, so
  `design/external-interfaces/` holds an `interface`.
* **`loom` is now a build dependency.** It is a separate tool, not vendored, and
  a change to its model grammar can fail validation here. Mitigated by it being
  read-only over the corpus: a loom failure blocks a document check, never a
  build of the library. `mvn` never calls it.
