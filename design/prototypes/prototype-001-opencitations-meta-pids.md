---
title: Opencitations meta pids
status: draft
targetVersion: 0.2.0
tags:
- opencitations
- id-mapping
- prototype
HAS_FEATURE:
- feature:feat-004-opencitations-meta-enrichment.md
---

# Prototype: does OpenCitations Meta give a complete PID equivalence class?

Exploration behind [feat-004](/design/features/feat-004-opencitations-meta-enrichment.md)
and [roadmap idea 1](/design/roadmap/index.md). The question it answers: given one
identifier, does Meta return *all* the other identifiers for that entity, and
specifically a DOI when the citation endpoint had none? If so, Meta can act as
an id-mapping source that rescues citation links the Index v2 response cannot
represent.

## Prototype

Requires `curl` and `python3`. Rate limit is 180 requests/minute and responses
take 3–6 seconds.

```console
$ # run the prototype body directly, e.g. by pmid or by doi
bash -s -- pmid:30636200 < <(sed -n '/^```bash$/,$p' design/prototypes/prototype-001-opencitations-meta-pids.md | sed '1d' | sed '/^```$/,$d')
```

The body is the same script, runnable directly with any number of ids
(separated for the API by `__`):

```bash
#!/bin/bash
# usage: prototype-001-opencitations-meta-pids.sh <prefix:id> [...]
set -euo pipefail

if [ "$#" -eq 0 ]; then
	echo "usage: $0 <prefix:id> [<prefix:id> ...]" >&2
	echo "  prefixes: doi, pmid, pmcid, omid, openalex, issn, isbn" >&2
	exit 1
fi

ids=$(IFS=__; echo "$*")
url="https://api.opencitations.net/meta/v1/metadata/${ids}"

echo "GET $url"
curl -sS --max-time 60 -H 'Accept: application/json' "$url" | python3 -c '
import sys, json
try:
    rows = json.load(sys.stdin)
except Exception as e:
    sys.exit("could not parse response: %s" % e)
if isinstance(rows, dict):
    rows = [rows]
for r in rows:
    pids = (r.get("id") or "").split()
    by_scheme = {}
    for p in pids:
        by_scheme.setdefault(p.split(":", 1)[0], []).append(p)
    print()
    print("title   :", (r.get("title") or "")[:80])
    print("pub_date:", r.get("pub_date"), "| type:", r.get("type"))
    print("pids    :", len(pids))
    for scheme in sorted(by_scheme):
        print("   %-9s %s" % (scheme, " ".join(by_scheme[scheme])))
    print("representable as a DOI CitationLink:", "doi" in by_scheme)
'
```

## Observed result

For `pmid:30636200` (2026-09): four PIDs — `doi`, `pmid`, `omid`, `openalex`
— and `representable as a DOI CitationLink: True`. Metadata fields
(`title`, `pub_date`, `type`, `venue`) are populated; there is no abstract.


- No abstracts or citation links — Meta cannot replace the record fetchers.
- `publisher`/`editor` values embed identifiers in brackets and need parsing.
- Same service economics as Index v2 (180 req/min, 3–6 s latency), so the
  read-timeout and rate-limit changes from
  [ADR-003](/architecture/decision-records/adr-003-opencitations-index-v2.md)
  apply to any client built on it.

