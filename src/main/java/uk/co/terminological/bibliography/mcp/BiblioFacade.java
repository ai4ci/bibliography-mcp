package uk.co.terminological.bibliography.mcp;

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
import uk.co.terminological.bibliography.api.BibliographicApis;
import uk.co.terminological.bibliography.api.crossref.CrossRefWork;
import uk.co.terminological.bibliography.client.CitedByMapper;
import uk.co.terminological.bibliography.client.CitesMapper;
import uk.co.terminological.bibliography.client.IdMapper;
import uk.co.terminological.bibliography.client.Query;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.client.SearchBuilder;
import uk.co.terminological.bibliography.client.Searcher;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.CitationReference;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
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

	static Logger log = LoggerFactory.getLogger(BiblioFacade.class);

	BibliographicApis api;

	String citationStyle = null;

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

	public BiblioFacade withCitationStyle(String styleName) {
		this.citationStyle = styleName;
		return this;
	}

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

			Map<String, Query> multiSearch = new HashMap<>();
			for (String api : this.biblioFacade.searchApis) {
				multiSearch.put(api, this.qry);
			}
			List<Record> out = new ArrayList<>();
			this.biblioFacade.searchApis.forEach(api -> {
				Collection<? extends Record> tmp = Collections.emptyList();
				if (api.equals("crossref")) {
					tmp = this.biblioFacade.api.getCrossref()
						.search(this.qry);
				} else if (api.equals("entrez")) {
					tmp = this.biblioFacade.api.getEntrez()
						.search(this.qry);
				} else if (api.equals("europepmc")) {
					tmp = this.biblioFacade.api.getEuropePMC()
						.search(this.qry);
				}
				log.info("Search found {} hits in {}", tmp.size(), api);
				out.addAll(tmp);
			});

			return Optional.of(out);
		}

	}

	@Override
	public Collection<Record> search(Query qry) {
		Map<String, Query> multiSearch = new HashMap<>();
		for (String api : this.searchApis) {
			multiSearch.put(api, qry);
		}
		return this.multiSearch(multiSearch);
	}

	public Collection<Record> multiSearch(Map<String, Query> searchByApi) {
		Collection<Record> out = new ArrayList<>();
		searchByApi.forEach((searchApi, search) -> {
			Collection<? extends Record> tmp = Collections.emptyList();
			if (searchApi.equals("crossref")) {
				tmp = this.api.getCrossref()
					.search(search);
			} else if (searchApi.equals("entrez")) {
				tmp = this.api.getEntrez()
					.search(search);
			} else if (searchApi.equals("europepmc")) {
				tmp = this.api.getEuropePMC()
					.search(search);
			}
			log.info(
				"Search found {} hits in {} for '{}'",
				tmp.size(),
				searchApi,
				search
			);
			out.addAll(tmp);
		});

		return out;
	}

}
