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
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.UnionFind;
import uk.co.terminological.bibliography.client.Searcher;
import uk.co.terminological.bibliography.mcp.Session;
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

	Optional<Integer> limit = Optional.of(50);

	public static Map<String, List<String>> defaultApis() {
		Map<String, List<String>> out = new LinkedHashMap<>();
		out.put("search", List.of("crossref", "entrez", "europepmc"));
		// medRxiv joins the citation sources as the only route to a preprint's own
		// reference list, and ignores anything that is not a 10.1101 DOI
		out.put(
			"cites",
			List.of("crossref", "entrez", "europepmc", "opencitations", "medrxiv")
		);
		out.put("citedBy", List.of("opencitations", "entrez", "europepmc"));
		out.put(
			"fetch",
			List.of("crossref", "entrez", "europepmc", "unpaywall", "medrxiv")
		);
		// the preprint-to-published-article mapping
		out.put("idMap", List.of("pmcid"));
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
			} else if (fetchApi.equals("unpaywall")) {
				tmp = this.api.getUnpaywall()
					.fetch(ids);
			} else if (fetchApi.equals("medrxiv")) {
				tmp = this.api.getMedrxiv()
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
			} else if (fetchApi.equals("unpaywall")) {
				fetched = this.api.getUnpaywall()
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

	public BiblioFacade withSearchLimit(int limit) {
		this.limit = Optional.of(limit);
		return this;
	}

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
	@Override
	public Collection<Record> search(Query qry) {
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
	private Collection<Record> dedupedSearch(List<ScoredHit> scoredHits) {
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
		log.warn("No search available for API: {}", api);
		return List.of();
	}

}
