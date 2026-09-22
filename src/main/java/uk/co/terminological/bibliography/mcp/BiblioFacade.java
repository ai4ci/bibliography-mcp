package uk.co.terminological.bibliography.mcp;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import uk.co.terminological.bibliography.BibliographicApis;
import uk.co.terminological.bibliography.client.CitedByMapper;
import uk.co.terminological.bibliography.client.CitesMapper;
import uk.co.terminological.bibliography.client.IdMapper;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.client.Searcher;
import uk.co.terminological.bibliography.crossref.CrossRefWork;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;

/**
 * Plain-Java facade over the bibliographic clients: routes search / citation /
 * fetch / id-mapping queries across the configured APIs and merges results.
 * (Salvaged from the rjavaapi ApiClient, minus the R interface.)
 */
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

	Optional<LocalDate> from = Optional.empty();
	Optional<LocalDate> to = Optional.empty();
	Optional<Integer> limit = Optional.of(50);

	public static Map<String, List<String>> defaultApis() {
		Map<String, List<String>> out = new LinkedHashMap<>();
		out.put("search", List.of("crossref", "entrez", "europepmc"));
		out.put("cites", List.of("crossref", "entrez", "europepmc", "opencitations"));
		out.put("citedBy", List.of("opencitations", "entrez", "europepmc"));
		out.put("fetch", List.of("crossref", "entrez", "europepmc", "unpaywall"));
		out.put("idMap", List.of("pmcid"));
		return out;
	}

	public BiblioFacade(java.nio.file.Path configFile) throws IOException {
		this.api = BibliographicApis.create(configFile);
	}

	public BiblioFacade(java.nio.file.Path configFile, java.nio.file.Path cacheDir) throws IOException {
		this.api = BibliographicApis.create(configFile, cacheDir);
	}

	public void enableCache() { this.api.enableCache(); }

	public void disableCache() { this.api.disableCache(); }

	@Override
	public Collection<Record> search(
			String search, Optional<LocalDate> from, Optional<LocalDate> to,
			Optional<Integer> limit
	) {
		Map<String, String> multiSearch = new HashMap<>();
		for (String api : this.searchApis) {
			multiSearch.put(api, search);
		}
		return this.multiSearch(multiSearch, from, to, limit);
	}

	public Collection<Record> multiSearch(
			Map<String, String> searchByApi, Optional<LocalDate> from,
			Optional<LocalDate> to, Optional<Integer> limit
	) {
		Collection<Record> out = new ArrayList<>();
		searchByApi.forEach((searchApi, search) -> {
			Collection<? extends Record> tmp = Collections.emptyList();
			if (searchApi.equals("crossref")) {
				tmp = this.api.getCrossref()
					.search(search, from, to, limit);
			} else if (searchApi.equals("entrez")) {
				tmp = this.api.getEntrez()
					.search(search, from, to, limit);
			} else if (searchApi.equals("europepmc")) {
				tmp = this.api.getEuropePMC()
					.search(search, from, to, limit);
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

	@Override
	public Collection<? extends CitationLink> citesReferences(
			Collection<? extends RecordReference> refs
	) {
		Collection<CitationLink> out = new ArrayList<>();
		this.citesApiLoop(refs, out);
		return out;
	}

	private void citesApiLoop(
			Collection<? extends RecordReference> refs, Collection<CitationLink> out
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
				.flatMap(ref -> this.api.getCrossref()
					.findWorkByCitationString(ref)
					.stream())
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

	public BiblioFacade withSearchDates(String from, String to) {
		try {
			this.from = Optional.ofNullable(from)
				.map(LocalDate::parse);
			this.to = Optional.ofNullable(to)
				.map(LocalDate::parse);
		} catch (DateTimeParseException e) {
			log.error("Unparseable date string in one of: {} or {}", from, to);
		}
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

}
