package uk.co.terminological.bibliography.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.undercouch.citeproc.CSL;
import uk.co.terminological.bibliography.api.crossref.CrossRefWork;
import uk.co.terminological.bibliography.client.CitedByMapper;
import uk.co.terminological.bibliography.client.CitesMapper;
import uk.co.terminological.bibliography.client.IdMapper;
import uk.co.terminological.bibliography.client.Query;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.client.SearchBuilder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.UnionFind;
import uk.co.terminological.bibliography.client.Searcher;
import uk.co.terminological.bibliography.mcp.Session;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.CitationReference;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;

/**
 * Plain-Java facade over the bibliographic clients: routes search / citation / fetch / id-mapping queries across the
 * configured APIs and merges results. (Salvaged from the rjavaapi ApiClient, minus the R interface.)
 */
// [IMPLEMENTS](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md)
// [IMPLEMENTS](/design/external-interfaces/interface-002-configuration-and-secrets.md)

public class BiblioFacade
		implements Searcher, CitedByMapper, CitesMapper, RecordFetcher, IdMapper {

	// TODO: implement CachingApiClient and sllow setup to create persistent id mapping
	// inbuilt resolvers to map between different id schemes to persistent internal id

	static Logger log = LoggerFactory.getLogger(BiblioFacade.class);

	BibliographicApis api;

	List<String> searchApis = new ArrayList<>(defaultApis().get("search"));

	List<String> citesApis = new ArrayList<>(defaultApis().get("cites"));

	List<String> citedByApis = new ArrayList<>(defaultApis().get("citedBy"));

	List<String> fetchApis = new ArrayList<>(defaultApis().get("fetch"));

	List<String> idMapApis = new ArrayList<>(defaultApis().get("idMap"));

	/**
	 * The APIs {@link #dispatchSearch} can route a search to, in the order they are asked.
	 *
	 * The single list of them, because there were two: the MCP tool's schema offered an API the dispatcher had no branch
	 * for, so asking for it was a search that could only ever return nothing (issue-020). Anything that advertises what
	 * can be searched reads this rather than keeping its own copy.
	 *
	 * OpenAlex is here despite being the only metered source — 10 credits a call against a daily allowance — because it
	 * is gated rather than merely registered: see {@link #searchOpenAlex}, where a search that would eat into the reserve
	 * is skipped and the free sources still answer.
	 *
	 * medRxiv is deliberately absent. bioRxiv serves a Cloudflare challenge to any programmatic client, so its search
	 * interface cannot be reached from here; preprints are searched through Europe PMC, which indexes both servers
	 * essentially completely (issue-015).
	 */
	public static List<String> searchableApis() {
		return List.of("crossref", "entrez", "europepmc", "openalex");
	}

	public static Map<String, List<String>> defaultApis() {
		Map<String, List<String>> out = new LinkedHashMap<>();
		out.put("search", searchableApis());
		// medRxiv joins the citation sources as the only route to a preprint's own
		// reference list, and ignores anything that is not a 10.1101 DOI
		out.put(
			"cites",
			List.of("crossref", "entrez", "europepmc", "opencitations", "medrxiv")
		);
		// OpenAlex joins citedBy as the only source that answers "what cites this"
		// for a preprint, and it is the only one that costs credits: a cited-by crawl
		// is 1 credit per page, so the budget is the thing to watch there, not the rate
		out.put("citedBy", List.of("opencitations", "entrez", "europepmc", "openalex"));
		out.put(
			"fetch",
			List.of(
				"crossref", "entrez", "europepmc", "medrxiv",
				"openalex"
			)
		);
		// The preprint-to-published-article mapping. OpenAlex follows pmcid: idconv is
		// the free specialist for DOI/PMID/PMCID and is asked first, while OpenAlex
		// resolves OpenAlex ids and the identifier types idconv has never heard of.
		out.put("idMap", List.of("pmcid", "openalex"));
		return out;
	}

	public BiblioFacade(java.nio.file.Path configFile) throws IOException {
		this.api = BibliographicApis.create(configFile);
	}

	public BiblioFacade(
			java.nio.file.Path configFile, java.nio.file.Path cacheDir
	) throws IOException {
		this.api = BibliographicApis.create(configFile, cacheDir);
	}

	public void enableCache() {
		this.api.enableCache();
	}

	public void disableCache() {
		this.api.disableCache();
	}

	@Override
	public Collection<? extends CitationLink> citesReferences(
			Collection<? extends RecordReference> refs
	) {
		Collection<CitationLink> out = new ArrayList<>();
		this.citesApiLoop(refs, out);
		return dedupeCitations(out);
	}

	/**
	 * One citation reported by two APIs is one citation. Without this the counts an agent sees are inflated by however many
	 * sources happen to cover the same reference list — which is now the normal case for a preprint, whose references come
	 * from both Europe PMC and its own JATS.
	 *
	 * An endpoint is identified by its identifier, falling back to its title. When neither is available the link is **kept
	 * as it stands** rather than collapsed: a shared "unknown" key would quietly merge every unidentified reference into
	 * one, which is worse than a duplicate.
	 */

	static Collection<CitationLink> dedupeCitations(
			Collection<? extends CitationLink> links
	) {
		List<CitationLink> out = new ArrayList<>();
		Map<String, CitationLink> byPair = new LinkedHashMap<>();
		for (CitationLink link : links) {
			var key = pairKey(link);
			if (key == null) { out.add(link); continue; }
			// keep the richest version of the pair: two APIs may describe the same
			// citation and one of them may have no title or no target identifier,
			// and taking whichever came first would throw the better one away
			byPair.merge(
				key,
				link,
				(first, second) -> richness(second) > richness(first) ? second
					: first
			);
		}
		out.addAll(byPair.values());
		return out;
	}

	/** how much a link tells us, for choosing between two descriptions of one citation */
	private static int richness(CitationLink link) {
		var score = 0;
		if (identifiable(link.getTarget()).isPresent()) { score += 2; }
		if (link.getTarget()
			.getTitle()
			.isPresent()) { score += 1; }
		if (link.getSource()
			.getTitle()
			.isPresent()) { score += 1; }
		if (link.getIndex()
			.isPresent()) { score += 1; }
		return score;
	}

	private static String pairKey(CitationLink link) {
		var source = identifiable(link.getSource());
		var target = identifiable(link.getTarget());
		if (!source.isPresent() || !target.isPresent()) return null;
		return source.get() + "->" + target.get();
	}

	/** an endpoint by identifier, or by title when it has no identifier */
	private static Optional<String> identifiable(CitationReference reference) {
		return reference.getIdentifier()
			.flatMap(RecordReference::getIdentifier)
			.map(Optional::of)
			.orElseGet(reference::getTitle);
	}

	private void citesApiLoop(
			Collection<? extends RecordReference> refs,
			Collection<CitationLink> out
	) {
		this.citesApis.forEach(citesApi -> {
			Collection<? extends CitationLink> tmp = Collections.emptyList();
			if (citesApi.equals("crossref")) {
				tmp = this.api.getCrossref()
					.citesReferences(refs);
			} else if (citesApi.equals("entrez")) {
				tmp = this.api.getEntrez()
					.citesReferences(refs);
			} else if (citesApi.equals("europepmc")) {
				tmp = this.api.getEuropePMC()
					.citesReferences(refs);
			} else if (citesApi.equals("opencitations")) {
				tmp = this.api.getOpenCitationsClient()
					.citesReferences(refs);
			} else if (citesApi.equals("medrxiv")) {
				tmp = this.api.getMedrxiv()
					.citesReferences(refs);
			} else if (citesApi.equals("openalex")) {
				tmp = this.api.getOpenAlex()
					.citesReferences(refs);
			}
			log.info(
				"Cites search found {} references in {} for {} articles",
				tmp.size(),
				citesApi,
				refs.size()
			);
			out.addAll(tmp);
		});
	}

	@Override
	public Collection<? extends CitationLink> referencesCiting(
			Collection<? extends RecordReference> refs
	) {
		log.info(
			"Searching for articles citing these {} references",
			refs.size()
		);
		Collection<CitationLink> out = new ArrayList<>();
		this.citedByApis.forEach(citesApi -> {
			Collection<? extends CitationLink> tmp = Collections.emptyList();
			if (citesApi.equals("entrez")) {
				tmp = this.api.getEntrez()
					.referencesCiting(refs);
			} else if (citesApi.equals("europepmc")) {
				tmp = this.api.getEuropePMC()
					.referencesCiting(refs);
			} else if (citesApi.equals("opencitations")) {
				tmp = this.api.getOpenCitationsClient()
					.referencesCiting(refs);
			} else if (citesApi.equals("openalex")) {
				tmp = this.api.getOpenAlex()
					.referencesCiting(refs);
			}
			log.info(
				"Cited by search found {} references in {} for {} articles",
				tmp.size(),
				citesApi,
				refs.size()
			);
			out.addAll(tmp);
		});
		return out;
	}

	@Override
	public Map<ImmutableRecordReference, ? extends Record> fetch(
			Collection<? extends RecordReference> ids
	) {
		Map<ImmutableRecordReference, MergedRecord> out = new HashMap<>();
		this.fetchApis.forEach(fetchApi -> {
			Map<ImmutableRecordReference, ? extends Record> tmp = new HashMap<>();
			if (fetchApi.equals("crossref")) {
				tmp = this.api.getCrossref()
					.fetch(ids);
			} else if (fetchApi.equals("entrez")) {
				tmp = this.api.getEntrez()
					.fetch(ids);
			} else if (fetchApi.equals("europepmc")) {
				tmp = this.api.getEuropePMC()
					.fetch(ids);
			} else if (fetchApi.equals("medrxiv")) {
				tmp = this.api.getMedrxiv()
					.fetch(ids);
			} else if (fetchApi.equals("openalex")) {
				tmp = this.api.getOpenAlex()
					.fetch(ids);
			}
			log.info(
				"Fetch found {} records in {} for {} ids",
				tmp.size(),
				fetchApi,
				ids.size()
			);
			tmp.forEach((k, v) -> {
				out.merge(k, MergedRecord.from(v), MergedRecord::merge);
			});
		});
		return out;
	}

	/**
	 * The per-API records, unmerged, keyed by source name. The merged view is what most callers want, but it keeps only the
	 * winning value and one provenance, so a caller that needs to attribute each value to the API that asserted it (the
	 * metadata clean-up report, for instance) needs the contributions separately.
	 */

	public Map<String, List<Record>> fetchBySource(
			Collection<? extends RecordReference> ids
	) {
		Map<String, List<Record>> out = new LinkedHashMap<>();
		this.fetchApis.forEach(fetchApi -> {
			Map<ImmutableRecordReference, ? extends Record> fetched;
			if (fetchApi.equals("crossref")) {
				fetched = this.api.getCrossref()
					.fetch(ids);
			} else if (fetchApi.equals("entrez")) {
				fetched = this.api.getEntrez()
					.fetch(ids);
			} else if (fetchApi.equals("europepmc")) {
				fetched = this.api.getEuropePMC()
					.fetch(ids);
			} else if (fetchApi.equals("openalex")) {
				fetched = this.api.getOpenAlex()
					.fetch(ids);
			} else {
				fetched = new HashMap<>();
			}
			List<Record> records = new ArrayList<>();
			fetched.values()
				.forEach(records::add);
			if (!records.isEmpty()) { out.put(fetchApi, records); }
		});
		log.info(
			"Unmerged fetch returned {} sources for {} ids",
			out.size(),
			ids.size()
		);
		return out;
	}

	@Override
	public Set<RecordReferenceMapping> mappings(
			Collection<? extends RecordReference> ids
	) {
		Set<RecordReferenceMapping> out = new HashSet<>();
		this.idMapApis.forEach(idMapApi -> {
			Collection<? extends RecordReferenceMapping> tmp = Collections
				.emptyList();
			if (idMapApi.equals("entrez")) {
				tmp = this.api.getEntrez()
					.mappings(ids);
			} else if (idMapApi.equals("pmcid")) {
				tmp = this.api.getPmcIdConv()
					.mappings(ids);
			} else if (idMapApi.equals("openalex")) {
				tmp = this.api.getOpenAlex()
					.mappings(ids);
			}
			log.info(
				"Mapping id search found {} mappings in {} for {} ids",
				tmp.size(),
				idMapApi,
				ids.size()
			);
			out.addAll(tmp);
		});
		return out;
	}

	public Set<CrossRefWork> getReferencesFromPdf(String pdfUrl)
			throws IOException {

		var ois = this.api.getPdfFetcher()
			.getPdfFromUrl(pdfUrl);
		if (ois.isPresent()) {

			var refs = this.api.getPdfFetcher()
				.extractArticleRefs(pdfUrl, ois.get());
			log.debug("Found {} references for {}", refs.size(), pdfUrl);

			Set<CrossRefWork> works = refs.stream()
				.flatMap(
					ref -> this.api.getCrossref()
						.findWorkByCitationString(ref)
						.stream()
				)
				.collect(Collectors.toSet());

			ois.get()
				.close();
			return works;

		} else {
			log.warn("Could not get pdf from {}", pdfUrl);
			return Collections.emptySet();
		}
	}

	// Fluent configuration

	public BiblioFacade withSearchApis(List<String> apis) {
		this.searchApis = apis;
		return this;
	}

	public BiblioFacade withFetchApis(List<String> apis) {
		this.fetchApis = apis;
		return this;
	}

	public BiblioFacade withCitesApis(List<String> apis) {
		this.citesApis = apis;
		return this;
	}

	public BiblioFacade withCitedByApis(List<String> apis) {
		this.citedByApis = apis;
		return this;
	}

	public BiblioFacade withIdMappingApis(List<String> apis) {
		this.idMapApis = apis;
		return this;
	}

	public List<String> getSupportedCitationStyles() {
		try {
			return new ArrayList<>(CSL.getSupportedStyles());
		} catch (IOException e) {
			log.warn("couldn't retrieve styles");
			return Collections.emptyList();
		}
	}

	public Session getSession() { return new Session(this); }

	public Session loadSession(String filename)
			throws IOException, ClassNotFoundException {
		filename = filename.replaceFirst("^~", System.getProperty("user.home"));
		var in = new java.io.FileInputStream(filename);
		var s = new java.io.ObjectInputStream(in);
		var out = (Session) s.readObject();
		in.close();
		out.setFacade(this);
		return out;
	}

	@Override
	public GenericSearchBuilder buildQuery() {
		return new GenericSearchBuilder(this);
	}

	public static class GenericSearchBuilder
			implements SearchBuilder<List<Record>> {

		private BiblioFacade biblioFacade;
		Query qry;

		public GenericSearchBuilder(BiblioFacade biblioFacade) {
			this.biblioFacade = biblioFacade;
		}

		@Override
		public GenericSearchBuilder withQuery(Query qry) {
			this.qry = qry;
			return this;
		}

		@Override
		public Optional<List<Record>> execute() {
			// delegate to the facade's search(), which now does dedup + RRF
			return Optional.of(new ArrayList<>(this.biblioFacade.search(this.qry)));
		}

	}

	/**
	 * A raw search hit tagged with the API that returned it and its 1-indexed
	 * position in that API's result list. Used after dedup to compute RRF scores.
	 */
	/** Visible for testing. */
	static class ScoredHit {
		final Record record;
		final String api;
		final int rank; // 1-indexed position in the api's result list

		ScoredHit(Record record, String api, int rank) {
			this.record = record;
			this.api = api;
			this.rank = rank;
		}
	}

	/**
	 * Collect and deduplicate raw hits from each configured search API, then
	 * rank by reciprocal rank fusion (RRF).
	 * 
	 * @see #dedupedSearch(List)
	 */
	/** a DOI, in any of the spellings people paste: bare, prefixed, or as a resolver URL */
	private static final java.util.regex.Pattern DOI = java.util.regex.Pattern
		.compile(
			"(?:https?://(?:dx\\.)?doi\\.org/|doi:)?(10\\.\\d{4,9}/\\S+)",
			java.util.regex.Pattern.CASE_INSENSITIVE
		);

	private static final java.util.regex.Pattern PMCID = java.util.regex.Pattern
		.compile("(?:https?://\\S+/)?(PMC\\d{4,})", java.util.regex.Pattern.CASE_INSENSITIVE);

	private static final java.util.regex.Pattern OPENALEX = java.util.regex.Pattern
		.compile("(?:https?://openalex\\.org/)?(W\\d{6,})", java.util.regex.Pattern.CASE_INSENSITIVE);

	/** an explicit PMID, or a bare number long enough that it cannot be a year somebody typed */
	private static final java.util.regex.Pattern PMID = java.util.regex.Pattern
		.compile("(?:pmid:\\s*(\\d{1,8})|(\\d{7,8}))", java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * The record a query names, when the query is nothing but an identifier.
	 *
	 * A search matches text, and an identifier put through one matches every paper that mentions it — a DOI searched
	 * rather than fetched returned 305 works where the caller wanted one. An identifier does not describe a paper, it
	 * names one, so a query that carries nothing else is answered by {@link #fetch} instead.
	 *
	 * "Nothing else" is strict on purpose. A DOI alongside an author or a date window is a caller filtering rather than
	 * looking something up, and a fetch cannot honour the rest of what they asked for, so that stays a search.
	 *
	 * A bare number is only read as a PMID at seven or eight digits. Shorter ones are years and quantities typed into a
	 * search box, and turning those into a lookup would answer an ordinary search with nothing.
	 */
	static Optional<ImmutableRecordReference> identifierQuery(Query qry) {
		if (!qry.getTitleAbstract()
			.isEmpty() || !qry.getFullText()
				.isEmpty() || !qry.getAuthors()
					.isEmpty() || !qry.getJournal()
						.isEmpty() || !qry.getNativeQuery()
							.isEmpty() || !qry.getAfter()
								.isEmpty() || !qry.getUntil()
									.isEmpty())
			return Optional.empty();
		return qry.getAllFields()
			.map(String::trim)
			.flatMap(BiblioFacade::identifierIn);
	}

	private static Optional<ImmutableRecordReference> identifierIn(String term) {
		// DOI first: a DOI contains digits that the PMID pattern would otherwise
		// recognise, and the whole term has to match so that a title mentioning a DOI
		// stays a search
		var doi = DOI.matcher(term);
		if (doi.matches()) return Optional.of(
				Builder.recordReference(IdType.DOI, doi.group(1))
		);
		var pmcid = PMCID.matcher(term);
		if (pmcid.matches()) return Optional.of(
				Builder.recordReference(
					IdType.PMCID,
					pmcid.group(1)
						.toUpperCase()
				)
		);
		var openalex = OPENALEX.matcher(term);
		if (openalex.matches()) return Optional.of(
				Builder.recordReference(
					IdType.OPENALEX,
					openalex.group(1)
						.toUpperCase()
				)
		);
		var pmid = PMID.matcher(term);
		if (pmid.matches()) return Optional.of(
				Builder.recordReference(
					IdType.PMID,
					pmid.group(1) == null ? pmid.group(2) : pmid.group(1)
				)
		);
		return Optional.empty();
	}

	@Override
	public Collection<Record> search(Query qry) {
		// An identifier names one record. Answering it with a text search is what put
		// 305 works in front of a caller who asked for one paper (issue-023). There is
		// deliberately no fallback to searching when the fetch finds nothing: a
		// mistyped DOI returning nothing says so, where a list of papers that merely
		// mention it would not.
		var named = identifierQuery(qry);
		if (named.isPresent()) {
			log.info("Query names {}; fetching it rather than searching", named.get());
			var fetched = this.fetch(List.of(named.get()))
				.values();
			if (fetched.isEmpty()) return List.of();
			// One identifier was asked about, so everything that came back describes
			// that one work — even where a client keyed its answer by an identifier of
			// its own rather than by the one it was given (issue-024), which would
			// otherwise hand the caller the same paper twice.
			MergedRecord one = null;
			for (Record r : fetched)
				one = one == null ? MergedRecord.from(r) : one.merge(r);
			return List.of(one);
		}
		List<ScoredHit> scoredHits = new ArrayList<>();
		for (String api : this.searchApis) {
			Collection<? extends Record> raw = dispatchSearch(api, qry);
			List<? extends Record> results = raw instanceof List
				? (List<? extends Record>) raw
				: new ArrayList<>(raw);
			for (int i = 0; i < results.size(); i++) {
				scoredHits.add(new ScoredHit(results.get(i), api, i + 1));
			}
			log.info(
				"Search found {} hits in {} for '{}'",
				results.size(),
				api,
				qry
			);
		}
		return dedupedSearch(scoredHits);
	}

	/**
	 * Search each API with its own query, deduplicate and rank by RRF.
	 * Each entry in {@code searchByApi} uses that API's individual query.
	 */
	public Collection<Record> multiSearch(Map<String, Query> searchByApi) {
		List<ScoredHit> scoredHits = new ArrayList<>();
		searchByApi.forEach((api, qry) -> {
			Collection<? extends Record> raw = dispatchSearch(api, qry);
			List<? extends Record> results = raw instanceof List
				? (List<? extends Record>) raw
				: new ArrayList<>(raw);
			for (int i = 0; i < results.size(); i++) {
				scoredHits.add(new ScoredHit(results.get(i), api, i + 1));
			}
			log.info(
				"Search found {} hits in {} for '{}'",
				results.size(),
				api,
				qry
			);
		});
		return dedupedSearch(scoredHits);
	}

	/**
	 * Deduplicate a list of scored hits (Phase 2–5) and return merged records
	 * sorted by RRF score descending.
	 * <p>
	 * Phase 2: collect every identifier (DOI, PMID, PMCID) from all results.
	 * Phase 3: run through IdMapper to find cross-scheme equivalences.
	 * Phase 4: group by equivalence and merge via {@link MergedRecord}.
	 * Phase 5: compute RRF score per group and sort descending.
	 */
	/** Visible for testing: the dedup is worth asserting without a search in front of it. */
	Collection<Record> dedupedSearch(List<ScoredHit> scoredHits) {
		if (scoredHits.isEmpty()) return List.of();

		// Phase 2 — collect all identifiers
		Set<ImmutableRecordReference> allIds = new HashSet<>();
		for (ScoredHit sh : scoredHits) {
			Record r = sh.record;
			r.getIdentifier()
				.ifPresent(id -> allIds.add(refOf(r)));
			for (RecordReference other : r.getOtherIdentifiers()) {
				other.getIdentifier()
					.ifPresent(id -> allIds.add(otherOf(other)));
			}
		}

		// Phase 3 — map identifiers across schemes
		Set<RecordReferenceMapping> mappings = mappings(allIds);
		UnionFind<ImmutableRecordReference> uf = new UnionFind<>();
		for (ImmutableRecordReference id : allIds)
			uf.add(id);
		for (RecordReferenceMapping m : mappings) {
			if (!m.isComplete()) continue;
			uf.union(refOf(m.getSource()), refOf(m.getTarget()));
		}

		// Phase 4 — group by equivalence root
		Map<ImmutableRecordReference, MergedRecord> merged = new LinkedHashMap<>();
		Map<ImmutableRecordReference, List<ScoredHit>> hitsByRoot = new LinkedHashMap<>();

		for (ScoredHit sh : scoredHits) {
			ImmutableRecordReference root = rootRef(sh.record, uf);
			if (root == null) continue;
			merged.merge(root, MergedRecord.from(sh.record), MergedRecord::merge);
			hitsByRoot.computeIfAbsent(root, k -> new ArrayList<>())
				.add(sh);
		}

		// Phase 5 — rank by RRF and sort
		int k = 5;
		List<Map.Entry<ImmutableRecordReference, MergedRecord>> sorted = new ArrayList<>(
			merged.entrySet()
		);
		sorted.sort((a, b) -> Double.compare(rrfScore(hitsByRoot.get(b.getKey()), k),
			rrfScore(hitsByRoot.get(a.getKey()), k)));

		List<Record> out = new ArrayList<>(sorted.size());
		for (var entry : sorted)
			out.add(entry.getValue());
		return out;
	}

	/** Visible for testing. */
	static double rrfScore(List<ScoredHit> hits, int k) {
		return hits.stream()
			.mapToDouble(sh -> 1.0 / (k + sh.rank))
			.sum();
	}

	/** Convenience: build an ImmutableRecordReference from a record's own identifier. */
	private static ImmutableRecordReference refOf(RecordReference ref) {
		return ImmutableRecordReference.builder()
			.identifierType(ref.getIdentifierType())
			.identifier(ref.getIdentifier()
				.orElse(null))
			.build();
	}

	/** Convenience: build an ImmutableRecordReference from another reference. */
	private static ImmutableRecordReference otherOf(RecordReference ref) {
		return ImmutableRecordReference.builder()
			.identifierType(ref.getIdentifierType())
			.identifier(ref.getIdentifier()
				.orElse(null))
			.build();
	}

	/**
	 * Find the equivalence-class root for a record, or build one from its own
	 * identifier if it has no mapping yet.
	 */
	private static ImmutableRecordReference rootRef(Record r, UnionFind<ImmutableRecordReference> uf) {
		return r.getIdentifier()
			.map(id -> refOf(r))
			.flatMap(uf::find)
			.orElse(null);
	}

	/**
	 * Route a search to the right API client by name.
	 */
	private Collection<? extends Record> dispatchSearch(String api, Query qry) {
		if (api.equals("crossref")) return this.api.getCrossref()
			.search(qry);
		if (api.equals("entrez")) return this.api.getEntrez()
			.search(qry);
		if (api.equals("europepmc")) return this.api.getEuropePMC()
			.search(qry);
		if (api.equals("openalex")) return searchOpenAlex(qry);
		log.warn("No search available for API: {}", api);
		return List.of();
	}

	/**
	 * Search OpenAlex, unless today's credits are better spent elsewhere.
	 *
	 * The one search source that costs money. A search is 10 credits of a daily allowance and a fetch or citation call is
	 * 1, so the gate keeps a reserve for the roles OpenAlex is the only source for; below it OpenAlex drops out of the
	 * search and the free sources answer alone. That is a deliberate skip rather than a failure, and is logged as one.
	 *
	 * The refusal is also caught here rather than left to propagate: the budget can run out between the check and the
	 * call, and until issue-013 is dealt with an exception out of one client empties every other client's results too.
	 */
	private Collection<? extends Record> searchOpenAlex(Query qry) {
		var openalex = this.api.getOpenAlex();
		if (!openalex.canAffordSearch()) {
			log.info(
				"Skipping OpenAlex search: {} credits available, {} needed and a reserve kept for fetch and citation calls",
				openalex.creditsAvailable(),
				uk.co.terminological.bibliography.api.openalex.OpenAlexClient.SEARCH_COST
			);
			return List.of();
		}
		try {
			return openalex.search(qry);
		} catch (BibliographicApiException e) {
			log.warn("OpenAlex search refused: {}", e.getMessage());
			return List.of();
		}
	}

}
