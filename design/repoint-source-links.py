#!/usr/bin/env python3
"""Repoint design links at the source files that survive today.

The corpus links source files by path. The refactor moved classes between
packages, so ~40 recorded paths 404 and `loom validate` reports
graph/missing-source-file for each. Resolution is by filename: a name that
exists in exactly one place under src/ is unambiguous and gets rewritten.
Anything else is reported for a human, because guessing which class a design
meant would invent a link that looks authoritative and may not be.

Run once, then delete.
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")

SCHEMES = {"main": "src/main", "test": "src/test"}
LINK = re.compile(r"(\[(?:[A-Z][A-Z_]{2,})\]\()(/(?:src/(?:main|test))/[^)#]+?)(#[^)]*\))?(\))")
FRONT = re.compile(r"^(- )(main|test):(java/[^#\s]+?)(\s*(?:#.*)?)$")

by_name = defaultdict(list)
for scheme, root in SCHEMES.items():
    for path in (REPO / root).rglob("*.java"):
        by_name[path.name].append(f"{scheme}:{path.relative_to(REPO / root)}")

moves, ambiguous, missing = [], [], []


def resolve(scheme: str, tail: str):
    """`java/…/ZoteroClient.java` -> the current uri for that filename, or None."""
    name = tail.split("/")[-1]
    candidates = by_name.get(name, [])
    if len(candidates) == 1:
        return candidates[0]
    if len(candidates) > 1:
        same = [c for c in candidates if c.split(":")[0] == scheme]
        if len(same) == 1:
            return same[0]
        ambiguous.append((f"{scheme}:{tail}", candidates))
    else:
        missing.append(f"{scheme}:{tail}")
    return None


def rewrite(path: Path):
    raw = path.read_text()
    lines, changed = [], False
    for line in raw.split("\n"):
        front = FRONT.match(line)
        if front:
            target = resolve(front.group(2), front.group(3))
            if target and target != f"{front.group(2)}:{front.group(3)}":
                moves.append((f"{front.group(2)}:{front.group(3)}", target, str(path)))
                line = f"- {target}{front.group(4)}"
                changed = True
            else:
                lines.append(line)
                continue
            lines.append(line)
            continue
        match = LINK.search(line)
        if match:
            tail = match.group(2).lstrip("/")
            scheme = "main" if tail.startswith("src/main/") else "test"
            target = resolve(scheme, tail[len("src/main/"):] if scheme == "main"
                             else tail[len("src/test/"):])
            if target:
                uri = target.split(":", 1)[1]
                new = f"{match.group(1)}/{uri}{match.group(4) or ''}{match.group(5)}"
                if new != match.group(0):
                    moves.append((f"{scheme}:{tail}", target, str(path)))
                    line = line[:match.start()] + new + line[match.end():]
                    changed = True
        lines.append(line)
    if changed:
        path.write_text("\n".join(lines))


for pattern in ("design/**/*.md",):
    for md in sorted((REPO / "design").rglob("*.md")):
        if md.name in ("VALIDATION.md", "document-model.mmd"):
            continue
        rewrite(md)

print(f"repointed {len(moves)} links")
for old, new, origin in moves:
    print(f"  {old.split('/')[-1]:38} -> {new}")
print("\nambiguous (same filename in more than one place, left alone):")
for old, cands in ambiguous:
    print(f"  {old}\n      candidates: {cands}")
print("\nno file with that name anywhere (left alone):")
for old in sorted(set(missing)):
    print(f"  {old}")
