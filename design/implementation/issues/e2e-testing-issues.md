# Bug Report: Missing Author/Creator Information in `biblio` Records and Citation Formatting Support

## Summary
When fetching, searching, or collecting bibliographic records via the `biblio` MCP server (using Crossref, Europe PMC, or importing from Zotero), the resulting normalised records omit author/creator information. Additionally, while the server exposes a list of 9,000+ citation styles via `biblio_citation_styles`, there is no corresponding tool to format or render session records into those styles.

---

## Issue 1: Author/Creator Metadata Dropped in Normalised Records

### Expected Behaviour
Calling `biblio_fetch`, `biblio_search`, or storing records in the session repository (`biblio_add_records`, `biblio_session_records`) should include the publication's authors (e.g. an `author` or `creators` array containing family/given names), along with standard bibliographic fields such as volume, issue, and pages.

### Actual Behaviour
Author metadata is absent from the returned objects across all providers:

1. **Europe PMC via `biblio_fetch`**:
   ```json
   {
     "id": "10.1136/bmjqs-2018-008370",
     "idType": "DOI",
     "title": "Artificial intelligence, bias and clinical safety.",
     "journal": "BMJ Quality & Safety",
     "date": "2019-01-12",
     "url": "https://qualitysafety.bmj.com/content/qhc/28/3/231.full.pdf",
     "PMID": "30636200",
     "DOI": "10.1136/bmjqs-2018-008370",
     "PMCID": "6560460",
     "publicationType": "article-journal",
     "provenance": {
       "source": "europepmc",
       "nativeType": "Research Support, Non-U.S. Gov't; research-article; Journal Article"
     }
   }
   ```
   *(No `author` or `creators` field).*

2. **Crossref via `biblio_search`**:
   ```json
   {
     "id": "10.1136/bmjqs-2018-008370",
     "idType": "DOI",
     "title": "Artificial intelligence, bias and clinical safety",
     "journal": "BMJ Quality &amp; Safety",
     "date": "2019-01-12",
     "DOI": "10.1136/bmjqs-2018-008370",
     "publicationType": "article-journal",
     "publisher": "BMJ",
     "provenance": {
       "source": "crossref",
       "nativeType": "journal-article"
     }
   }
   ```
   *(Crossref API provides `author`, `volume`, `issue`, `page`, but these are dropped in the normalised representation).*

3. **Session Repository (`biblio_session_records`)**:
   After calling `biblio_add_records` or importing from Zotero, the session record contains `title`, `abstract`, `journal`, identifiers, and provenance, but still no author data.

---

## Issue 2: Citation Rendering Tool Missing

### Expected Behaviour
The tool `biblio_citation_styles` states: *"List supported citation styles for rendering references."* This implies there is a companion tool (e.g. `biblio_render_citations` or `biblio_format_references`) that accepts a style (such as `ieee` or `apa`) and outputs formatted reference strings or a bibliography for records in the session.

### Actual Behaviour
`biblio_citation_styles` lists ~9,481 CSL style names, but there is no tool exposed by the server to render or export records using any of these styles.

---

## Steps to Reproduce
1. Fetch or search for a known work, e.g. DOI `10.1136/bmjqs-2018-008370`.
2. Inspect the JSON returned by `biblio_fetch` or `biblio_search`.
3. Add the record to the session repository with `biblio_add_records`.
4. Inspect `biblio_session_records`.
5. Notice that `author` / `creators` is missing in all steps.

---

## Issue 3: Backend API Failures / Search Anomalies (Entrez and Crossref)

### 1. Entrez / PubMed Completely Non-Functional
- **Observed**: Any call to `biblio_search` with `apis: ["entrez"]` returns `[]` (empty list), regardless of query complexity:
  - Exact DOI: `biblio_search(apis=["entrez"], query="10.1136/bmjqs-2018-008370")` -> `[]`
  - PMID with UID tag: `biblio_search(apis=["entrez"], query="30636200[UID]")` -> `[]`
  - Plain PMID: `biblio_search(apis=["entrez"], query="30636200")` -> `[]`
  - Author query: `biblio_search(apis=["entrez"], query="Challen R[Author]")` -> `[]`
  - Generic term: `biblio_search(apis=["entrez"], query="asthma")` -> `[]`
- **Expected**: Entrez queries should return matching PubMed records.

### 2. Crossref Query Sensitivity & Relevance Matching
- **Observed**:
  - `biblio_search(apis=["crossref"], query="Challen artificial intelligence safety")` -> `[]`
  - `biblio_search(apis=["crossref"], query="\"Artificial intelligence, bias and clinical safety\" Challen")` -> `[]`
  - However, querying by the title alone: `biblio_search(apis=["crossref"], query="\"Artificial intelligence, bias and clinical safety\"")` returns 20 items, with the target paper appearing down at item 17.
- **Root Cause Suspected**: Crossref's `/works?query=` parameter performs a full-text disjunctive relevance match where combined author + title strings often fail to match unless separated into dedicated parameters (e.g. `query.title`, `query.author`, or `query.bibliographic`). When an author name is appended into the single free-text query string, Crossref scores many unrelated items higher or yields empty sets depending on boolean interpretation.

---

## Issue 4: Default Search Behaviour (Omitted `apis` Parameter)

### 1. Default `biblio_search` Returns Nothing (`[]`)
- **Observed**: When invoking `biblio_search` without an explicit `apis` parameter (e.g. `biblio_search(query="asthma")` or `biblio_search(query="\"Artificial intelligence, bias and clinical safety\"")`), the response is **always `[]`**.
- **Contrast with `biblio_collect_search`**: Calling `biblio_collect_search(query="asthma")` (which takes only `query`) collects 4 records into the session repository. However, all 4 records come exclusively from `europepmc`.
- **Multi-API List Behaviour**: Specifying `apis: ["crossref", "europepmc"]` only returns items from `europepmc`. Multi-backend results are not merged or unioned.

### 2. Expected Behaviour
When no `apis` list is supplied, `biblio_search` should:
1. Default to searching all available working APIs (or a sensible default subset: Crossref + Europe PMC + PubMed).
2. De-duplicate results across providers by canonical identifier (DOI, PMID).
3. Return a unified, merged list of normalised hits with provenance tags showing confirming sources.

---

---

## Issue 5: Gap and Overlap Analysis: Direct Zotero Tool vs. `biblio` Zotero Bridge

Both interfaces operate against the same local/user Zotero data store, but with divergent responsibilities, protocols, and limitations:

```
┌────────────────────────────────────────────────────────┐
│                   Zotero Data Store                    │
└───────────────▲────────────────────────▲───────────────┘
                │                        │
       Direct Zotero Tools      `biblio` MCP Bridge
       (Native CRUD / CSL)     (Bibliographic Graph)
```

### 1. Functional Overlap
* **Item Resolution**: Both can check whether an item exists in the library by DOI / title / key.
* **Collection Browsing**: Both list and inspect user collections.
* **Metadata Reading**: Both extract bibliographic metadata (title, dates, publication, tags).

### 2. Capabilities Unique to Direct Zotero Tools (`zotero_*`)
* **Full CRUD Operations**: Full capability to create (`zotero_item create`), update, patch, and delete items and collections.
* **Direct Citation & Bibliography Export**: `zotero_export` directly exports items or collections in CSL-JSON, BibTeX, BibLaTeX, RIS, or formatted bibliographies (`format: 'bib'`).
* **Attachment & Full-Text Management**: `zotero_attachment` handles file upload/download of PDFs; `zotero_fulltext` indexes extracted text for full-text search.
* **Tag Mutation**: Comprehensive manual and automatic tag assignment (`zotero_tags`).

### 3. Capabilities Unique to `biblio` MCP Bridge
* **In-Memory Citation Graph**: Traverses references (`biblio_cites`) and citations (`biblio_cited_by`), building an active directed graph (`expand_citation_graph`, `biblio_page_rank`).
* **Multi-Database Federation & Reconciliation**: Connects local Zotero holdings with live scholarly APIs (Crossref, PubMed/Entrez, Europe PMC).
* **Automated Audit & Gap Analysis**:
  * `biblio_zotero_check`: Cross-references session literature against existing Zotero records to avoid duplicate discovery.
  * `biblio_zotero_cleanup_report`: Compares Zotero metadata against authoritative publisher APIs field-by-field, highlighting metadata discrepancies, missing abstracts, or HTML entity noise.
* **Topic Modeling**: Runs LDA topic modeling across literature sets (`biblio_topic_model`).

### 4. Identified Gaps & Friction Points in `biblio` Bridge
* **Lossy Data Import**: When `biblio_zotero_import` reads an item from Zotero into the `biblio` session, it drops author/creator details and CSL field fidelity.
* **Write Barrier**: The bridge requires explicit single-use write authorisation and target write collection configuration, making writes (`biblio_zotero_publish`, `biblio_zotero_apply_updates`) more cumbersome than direct `zotero_item` updates.
* **Rendering Gap**: Despite `biblio` having deep graph and literature intelligence, it cannot format citations directly (unlike `zotero_export`).


### 1. The Core Problem with Raw JSON in Agent Tooling
Currently, `biblio` tools return extensive raw JSON structures. For bibliographic queries:
- **Token Inflation**: Searching for 20 papers yields tens of kilobytes of nested braces, repeated keys, and provenance objects. This rapidly blows through tool output limits (e.g. triggering 50 KB truncations) and consumes scarce agent context.
- **Cognitive/Parsing Friction**: Agents rarely need the full nested JSON AST when assessing literature; they need to quickly evaluate relevance, authors, venue, year, and abstract before selecting items to cite.
- **Copy-Paste Gap**: When drafting manuscripts or proposals, agents cannot directly use JSON; they have to reconstruct or re-format citations from raw fields manually.

### 2. Proposed Architecture: Backend JSON, Frontend Markdown
- **Storage/Repository**: Continue storing complete, lossless CSL-JSON / CiteProc schema records in the backend database/session store so citation graphs, deduplication, and export functions retain strict metadata fidelity.
- **Agent Output**: Return clean, formatted Markdown by default for tools like `biblio_search`, `biblio_fetch`, and `biblio_session_records`. Provide an optional parameter (e.g. `format: "markdown" | "json"`, default `"markdown"`) as an escape hatch for programmatic tooling.

### 3. Structured Query Interface vs. Brittle Free-Text Strings
Currently, `biblio_search` and `biblio_collect_search` accept only an unstructured `query: string`. As observed in Issue 3, dumping mixed terms (`"AI Safety Challen"`) into an unstructured string behaves unpredictably across different backends (Crossref degrades or returns empty, Europe PMC parses differently, and Entrez fails).

**Proposed Solution: Accept a Structured Query Object (with Free-Text Fallback)**
Allow searches to provide structured intent:
```typescript
interface SearchQuery {
  query?: string;           // Free-text fallback / general keywords
  title?: string;           // Specific title terms
  author?: string;          // Author surname / full name
  year?: number | string;   // Publication year or range
  doi?: string;             // Direct identifier
  journal?: string;         // Container title
}
```
**Backend Resolution by Provider:**
- **Crossref**: Cleanly maps to targeted query parameters (`query.title`, `query.author`, `query.bibliographic`). Crossref's engine excels when author and title are segmented, avoiding the false-negative drops seen with combined strings.
- **Entrez / PubMed**: Automatically compiles into NCBI search syntax, e.g. `(Challen[Author]) AND ("AI safety"[Title/Abstract])`.
- **Europe PMC**: Compiles into Lucene syntax, e.g. `AUTH:"Challen" AND TITLE:"AI safety"`.
- **Zotero (Local Bridge)**: Translates into Zotero's title/creator fields directly.

This removes the burden from LLM agents to guess the dialect-specific syntax of each bibliographic provider.

### 4. Session-Scoped Default Citation Style
To make citation usage effortless across a multi-turn session:
- **New tool: `biblio_set_citation_style`** (or `biblio_session_config`)
  - Parameters: `{ style: string }` (e.g. `"ieee"`, `"apa"`, `"vancouver"`, `"nature"`).
  - Validates against `biblio_citation_styles` and persists the selection in the current session state.
- **Automatic Rendering**:
  - Whenever records are fetched, listed, or added, the tool output renders the ready-to-use formatted reference according to the active session style, e.g.:
    > **[1]** R. Challen, J. Denny, M. Pitt, L. Gompels, T. Edwards, and K. Tsaneva-Atanasova, "Artificial intelligence, bias and clinical safety," *BMJ Qual. Saf.*, vol. 28, no. 3, pp. 231–237, Mar. 2019, doi: 10.1136/bmjqs-2018-008370.
  - Accompanied by a concise bounded abstract and relevant identifiers.
- **Benefits**:
  - The agent can immediately lift the formatted citation into draft prose or bibliographies without manual reconstruction.
  - Massively reduces token usage per tool call (often 60–80% smaller than raw JSON).
  - Eliminates output truncation errors during broad exploratory searches.

