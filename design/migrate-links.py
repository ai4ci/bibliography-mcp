#!/usr/bin/env python3
"""One-off migration: markdown link relations -> loom frontmatter relations.

Run once, then delete. Not part of the build; `loom validate` is the check from
now on.

The corpus recorded a relation as a markdown link whose alt text IS the
relation name: `- [HAS_FEATURE](/design/features/feat-001-x.md)`. Loom builds
edges from frontmatter keys only and never reads the body, so:

* every relation link becomes a frontmatter entry, in uri form (`feature:x.md`)
* a link alone on a bullet line is consumed — the entry replaces it
* a link woven into a sentence stays in the prose, but loses the ALL-CAPS alt
  text so nobody mistakes it for syntax loom reads (`[feat-007](/path.md)`);
  the sentence stays the human explanation of a relation the frontmatter states

Renames, because the mermaid grammar that declares design/document-model.mmd
rejects '-' in a class, attribute or relation name and '-' is illegal in a loom
uri scheme:
  target-version -> targetVersion    parent-feature -> HAS_FEATURE entry
  since-version  -> sinceVersion     depends-on     -> DEPENDS_ON entry
  tags: a, b     -> YAML list        (so +List~string~ tags can be declared)

Titles are derived from the numbered filename slug; loom labels links with a
`title` attribute and the corpus never had one.
"""
import re
import sys
from pathlib import Path

import yaml

REPO = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")

# uri scheme <- path prefix; longest prefix wins, so order matters. Schemes are
# lowercase because loom compares a uri's scheme against the model's class name
# with no case folding, and class names must match these.
ROOTS = [
    ("src/main/", "main:"),
    ("src/test/", "test:"),
    ("scripts/", "scripts:"),
    ("design/features/", "feature:"),
    ("design/implementation/plans/", "plan:"),
    ("design/implementation/issues/", "issue:"),
    ("design/implementation/debt/", "debt:"),
    ("design/test-scripts/", "testscript:"),
    ("design/external-interfaces/", "interface:"),
    ("design/prototypes/", "prototype:"),
    ("architecture/decision-records/", "adr:"),
    ("design/roadmap/", "roadmap:"),
]

RELATIONS = {
    "HAS_FEATURE", "HAS_PLAN", "HAS_TEST", "HAS_INTERFACE", "HAS_PROTOTYPE",
    "IMPLEMENTED_BY", "TESTED_BY", "IMPACTS", "DEPENDS_ON",
}

RENAMES = {"target-version": "targetVersion", "since-version": "sinceVersion"}
LINK = re.compile(r"\[([A-Z][A-Z_]{2,})\]\(([^)#]+?)(#[^)]*)?\)")
BARE = re.compile(r"^- \[([A-Z][A-Z_]{2,})\]\(([^)#]+?)(#[^)]*)?\)\s*$")
CONVENTION = re.compile(r"^<!--\s*@(?:required|optional):.*-->\s*$")
HEADING = re.compile(r"^#{1,6}\s")
NUMBERED_SLUG = re.compile(r"^[a-z]+-\d+-(.+)\.md$")
UNASSIGNED = {"master-SNAPSHOT", "unassigned", ""}


def design_uri(path: str):
    """`/design/features/x.md` -> `feature:x.md`; None if not a design document."""
    raw = path.split("#")[0].lstrip("/")
    if not raw.endswith(".md"):
        return None
    for prefix, scheme in ROOTS:
        if raw.startswith(prefix):
            return scheme + raw[len(prefix):]
    return None


def source_uri(path: str):
    """A relation target that is code or a script rather than a document."""
    raw = path.split("#")[0].lstrip("/")
    if raw.endswith(".md"):
        return None
    for prefix, scheme in ROOTS:
        if raw.startswith(prefix):
            return scheme + raw[len(prefix):]
    return None


def target_of(path: str):
    return design_uri(path) or source_uri(path)


def title_from(path: Path) -> str:
    match = NUMBERED_SLUG.match(path.name)
    slug = match.group(1) if match else path.stem
    return "Roadmap" if slug == "index" else slug.replace("-", " ").capitalize()


def in_code_fence(lines):
    fenced = False
    for line in lines:
        if line.lstrip().startswith("```"):
            fenced = not fenced
            yield line, True
            continue
        yield line, fenced


def drop_stranded_headings(lines):
    """Drop headings left with nothing under them once their bullets moved to the
    frontmatter. A heading followed by a deeper heading keeps its place; one
    followed by an equal or shallower heading (or the end) had no prose of its
    own and would render as an empty section."""
    def level(text):
        return len(text) - len(text.lstrip("#"))

    indexes = [i for i, line in enumerate(lines) if HEADING.match(line)]
    stranded = set()
    for position, index in enumerate(indexes):
        following = indexes[position + 1] if position + 1 < len(indexes) else None
        gap = lines[index + 1:following] if following is not None else lines[index + 1:]
        if following is None or all(not line.strip() for line in gap):
            if following is None or level(lines[following]) <= level(lines[index]):
                stranded.add(index)
    return [line for i, line in enumerate(lines) if i not in stranded]


def migrate(path: Path, report):
    raw = path.read_text()
    lines = raw.split("\n")
    front, body_start = {}, 0
    if lines[0] == "---":
        end = lines.index("---", 1)
        front = yaml.safe_load("\n".join(lines[1:end])) or {}
        body_start = end + 1
    else:
        report["skipped_no_frontmatter"].append(str(path))
        return

    kept, relations = [], {}
    for line, fenced in in_code_fence(lines[body_start:]):
        if CONVENTION.match(line):
            report["dropped_convention_comments"] += 1
            continue  # the model states each convention once, for every document
        if not fenced:
            for match in LINK.finditer(line):
                record(match.group(1), match.group(2), path, relations, report)
            bare = BARE.match(line)
            if bare and bare.group(1) in RELATIONS and target_of(bare.group(2)):
                continue  # consumed: the frontmatter entry replaces the bullet
            line = humanise(line)
        kept.append(line)

    # A heading whose only content was relation bullets would be left stranded.
    # Drop it; a heading followed by a deeper heading is ordinary structure.
    body = "\n".join(drop_stranded_headings(kept))

    out = {"title": front.get("title") or title_from(path)}
    for key, value in front.items():
        if key in ("parent-feature", "depends-on", "title", "type"):
            continue  # `type` was the hand-written class marker; the folder says it
        out[RENAMES.get(key, key)] = value
    if isinstance(out.get("tags"), str):
        out["tags"] = [t.strip() for t in out["tags"].split(",") if t.strip()]
    if not out.get("tags"):
        out.pop("tags", None)
    if str(out.get("targetVersion")) in UNASSIGNED:
        out["targetVersion"] = "unassigned"
    if "targetVersion" not in out:
        out["targetVersion"] = "unassigned"
        report["target_version_backfilled"].append(str(path))
    if "status" not in out:
        out["status"] = "open" if "/issues/" in str(path) else "draft"
        report["status_backfilled"].append(str(path))
    for rel in sorted(relations):
        out[rel] = sorted(set(relations[rel]))
    for key, scheme in (("parent-feature", "feature:"), ("depends-on", "plan:")):
        if key in front:
            rel = "HAS_FEATURE" if key == "parent-feature" else "DEPENDS_ON"
            target = scheme + str(front[key])
            target = target if target.endswith(".md") else target + ".md"
            out[rel] = sorted(set(out.get(rel, []) + [target]))

    dumped = yaml.dump(out, sort_keys=False, allow_unicode=True, width=1000).rstrip("\n")
    text = "---\n" + dumped + "\n---\n\n" + body.lstrip("\n")
    if not text.endswith("\n"):
        text += "\n"
    if text != raw:
        path.write_text(text)
        report["written"].append(str(path))


def humanise(line: str) -> str:
    """Drop the ALL-CAPS alt text from a relation link left in prose, so it
    reads as an ordinary document reference rather than frontmatter syntax."""
    def repl(match):
        rel, target = match.group(1), match.group(2)
        if rel not in RELATIONS or not target_of(target):
            return match.group(0)
        label = target.split("/")[-1].removesuffix(".md")
        return f"[{label}]({target})"
    return LINK.sub(repl, line)


def record(rel, target, origin, relations, report):
    if rel not in RELATIONS:
        report["unknown_relation"].append(f"{origin}: [{rel}]({target})")
        return
    uri = target_of(target)
    if uri is None:
        report["unmapped"].append(f"{origin}: [{rel}]({target})")
        return
    relations.setdefault(rel, []).append(uri)


def main():
    files = [f for pattern in ("design/**/*.md",) for f in (REPO / "design").rglob("*.md")]
    files += sorted((REPO / "architecture" / "decision-records").glob("*.md"))
    files = sorted(set(files))
    report = {k: [] for k in ("written", "unmapped", "unknown_relation",
                              "skipped_no_frontmatter", "target_version_backfilled",
                              "status_backfilled")}
    report["dropped_convention_comments"] = 0
    for path in files:
        if path.name in ("document-model.mmd", "VALIDATION.md", "SCOPE.md"):
            continue
        migrate(path, report)
    print(f"rewrote {len(report['written'])} of {len(files)} files; dropped "
          f"{report['dropped_convention_comments']} convention comments")
    for key in ("skipped_no_frontmatter", "unmapped", "unknown_relation",
                "status_backfilled", "target_version_backfilled"):
        for line in report[key]:
            print(f"{key}: {line}")


if __name__ == "__main__":
    main()
