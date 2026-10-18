package uk.co.terminological.bibliography.api.entrez;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.isomorphism.util.TokenBuckets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import uk.co.terminological.bibliography.BinaryData;
import uk.co.terminological.bibliography.CachingApiClient;
import uk.co.terminological.bibliography.PdfFetcher;
import uk.co.terminological.bibliography.api.BibliographicApiException;
import uk.co.terminological.bibliography.api.pmcidconv.PMCIDClient;
import uk.co.terminological.bibliography.client.CitedByMapper;
import uk.co.terminological.bibliography.client.CitesMapper;
import uk.co.terminological.bibliography.client.IdMapper;
import uk.co.terminological.bibliography.client.Query;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.client.SearchBuilder;
import uk.co.terminological.bibliography.client.Searcher;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;
import uk.co.terminological.fluentxml.Xml;
import uk.co.terminological.fluentxml.XmlException;

/*
 * http://www.ncbi.nlm.nih.gov/books/NBK25500/
 */
public class EntrezClient extends CachingApiClient
		implements Searcher, RecordFetcher, CitesMapper, CitedByMapper, IdMapper {

	// TODO: integrate CSL:
	// https://michel-kraemer.github.io/citeproc-java/api/1.0.1/de/undercouch/citeproc/csl/CSLItemDataBuilder.html
	// TODO: caching with https://hc.apache.org/httpcomponents-client-ga/tutorial/html/caching.html#storage and EHCache

	private String apiKey;
	private String appId;
	private String developerEmail;
	// private WebResource eSearchResource;
	// private WebResource eFetchResource;
	// private WebResource eLinkResource;
	// private String baseUrl;
	private static final Logger logger = LoggerFactory
		.getLogger(EntrezClient.class);
	private static final String ESEARCH = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi";
	private static final String EFETCH = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi";
	private static final String ELINK = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/elink.fcgi";

	private PMCIDClient idLookup;

	private static Map<String, EntrezClient> singleton = new HashMap<>();

	public static EntrezClient create(
			String apiKey, String appId, String developerEmail
	) {
		return create(apiKey, appId, developerEmail, null);
	}

	public static EntrezClient create(
			String apiKey, String appId, String developerEmail, Path cache
	) {

		if (!singleton.containsKey(apiKey)) {
			var tmp = new EntrezClient(
					apiKey, appId, developerEmail, Optional.ofNullable(cache)
			);
			singleton.put(apiKey, tmp);
		}
		return singleton.get(apiKey);

	}

	// "http://eutils.ncbi.nlm.nih.gov/entrez/eutils/"
	private EntrezClient(
			String apiKey, String appId, String developerEmail,
			Optional<Path> cache
	) {
		super(
				cache, TokenBuckets.builder()
					.withInitialTokens(9)
					.withCapacity(9)
					.withFixedIntervalRefillStrategy(9, 1, TimeUnit.SECONDS)
					.build()
		);
		// this.baseUrl = baseUrl;
		// eSearchResource = client.resource(this.baseUrl + ESEARCH);
		// eFetchResource = client.resource(this.baseUrl + EFETCH);
		// eLinkResource = client.resource(this.baseUrl + ELINK);
		this.apiKey = apiKey;
		this.appId = appId;
		this.developerEmail = developerEmail;
		this.idLookup = new PMCIDClient(
				developerEmail, appId, cache.map(c -> c.resolve("idlookup"))
		);
	}

	@Override
	protected MultivaluedMap<String, String> defaultApiParams() {
		MultivaluedMap<String, String> out = new MultivaluedMapImpl();
		out.add("api_key", this.apiKey);
		out.add("tool", this.appId);
		out.add("email", this.developerEmail);
		return out;
	}

	@Override
	public ESearchQueryBuilder buildQuery() {
		return new ESearchQueryBuilder(this);
	}

	protected enum ESearchFields {
		JOURNAL(
				"Journal"
		), AUTHOR(
				"Author"
		), TITLE_ABSTRACT(
				"Title/Abstract"
		), TEXT(
				"Text Word"
		), ALL(
				null
		);

		private String marker;
		ESearchFields(String m) {
			this.marker = m;
		}
		public String searchTerm(String value) {
			if (this.marker == null) return "(" + value + ")";
			return "(" + value + "[" + this.marker + "])";
		}
	}

	public static class ESearchQueryBuilder
			implements SearchBuilder<EntrezSearch> {
		MultivaluedMap<String, String> searchParams;
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd");
		List<String> terms = new ArrayList<>();

		boolean empty = true;
		EntrezClient client;

		protected WebResource get(WebResource searchService) {
			var tdmCopy = searchService;
			return tdmCopy.queryParams(this.searchParams);
		}

		protected <X> X post(WebResource searchService, Class<X> output) {
			var tdmCopy = searchService;
			return tdmCopy.post(output, this.searchParams);
		}

		protected ESearchQueryBuilder(EntrezClient client) {
			this.searchParams = client.defaultApiParams();
			this.searchParams.add("db", "pubmed");
			this.searchParams.add("retmax", "100000");
			this.searchParams.add("usehistory", "y");
			this.client = client;
		}

		public ESearchQueryBuilder searchDatabase(Database db) {
			this.searchParams.remove("db");
			this.searchParams.add(
				"db",
				db.name()
					.toLowerCase()
			);
			return this;
		}

		public ESearchQueryBuilder searchTerm(String term) {
			if (term != null && term.length() > 0) { this.terms.add(term); }
			return this;
		}

		@Override
		public ESearchQueryBuilder withQuery(Query qry) {
			qry.getNativeQuery()
				.ifPresent(this.terms::add);
			qry.getAllFields()
				.stream()
				.map(ESearchFields.ALL::searchTerm)
				.forEach(this.terms::add);
			qry.getTitleAbstract()
				.stream()
				.map(ESearchFields.TITLE_ABSTRACT::searchTerm)
				.forEach(this.terms::add);
			qry.getFullText()
				.stream()
				.map(ESearchFields.TEXT::searchTerm)
				.forEach(this.terms::add);
			qry.getAuthors()
				.stream()
				.map(ESearchFields.AUTHOR::searchTerm)
				.forEach(this.terms::add);
			qry.getJournal()
				.stream()
				.map(ESearchFields.JOURNAL::searchTerm)
				.forEach(this.terms::add);
			this.between(qry.getAfter(), qry.getUntil());
			qry.getLimit()
				.ifPresent(l -> this.limit(0, l));
			return this;
		}

		public ESearchQueryBuilder limit(int from, int count) {
			this.searchParams.remove("retstart");
			this.searchParams.remove("retmax");
			this.searchParams.add("retstart", Integer.toString(from));
			this.searchParams.add("retmax", Integer.toString(count));
			return this;
		}

		public ESearchQueryBuilder withinLastDays(int count) {
			this.searchParams.remove("reldate");
			this.searchParams.remove("datetype");
			this.searchParams.add("datetype", "edat");
			this.searchParams.add("reldate", Integer.toString(count));
			return this;
		}

		public ESearchQueryBuilder between(
				Optional<LocalDate> from, Optional<LocalDate> to
		) {
			if (from.isPresent() || to.isPresent()) {
				var fromStr = from.map(d -> d.format(this.formatter))
					.orElse("1900-01-01");
				var toStr = to.orElse(LocalDate.now())
					.format(this.formatter);
				this.searchParams.remove("mindate");
				this.searchParams.remove("datetype");
				this.searchParams.remove("maxdate");
				this.searchParams.add("datetype", "pdat");
				this.searchParams.add("mindate", fromStr);
				this.searchParams.add("datetype", "pdat");
				this.searchParams.add("maxdate", toStr);
			}
			return this;
		}

		public ESearchQueryBuilder restrictSearchToField(String field) {
			this.searchParams.remove("field");
			this.searchParams.add("field", field);
			return this;
		}

		/**
		 * The eSearch {@code term} this builder would send, assembled exactly as {@link #execute()} assembles it.
		 *
		 * Exposed so the translation from a {@link Query} can be asserted without a network call: the native syntax is the part
		 * most likely to be wrong, and it is otherwise only observable by making the request.
		 */
		public String term() {
			return this.terms.stream()
				.collect(Collectors.joining(" AND "));
		}

		/** The request parameters as they stand, for the same reason as {@link #term()}. Live, not a copy. */
		public MultivaluedMap<String, String> params() {
			return this.searchParams;
		}

		@Override
		public Optional<EntrezSearch> execute() {
			if (this.terms.isEmpty()) return Optional.empty();
			this.searchParams.add("term", this.term());

			return this.client.buildCall(ESEARCH, EntrezSearch.class)
				.withParams(this.searchParams)
				.withOperation(is -> {
					var resp = Xml.fromStream(is);
					return new EntrezSearch(resp.content());
				})
				.post(MediaType.APPLICATION_FORM_URLENCODED_TYPE);

		}

		@Override
		public String toString() {
			return keyFromApiQuery(ESEARCH, this.searchParams);
		}
	}

	// public static class ELinkQueryBuilder

	public enum Database {
		PUBMED, PMC, MESH, GENE;
		@Override
		public String toString() {
			return this.name()
				.toLowerCase();
		}
	}

	public List<String> findPMIdsBySearch(String searchTerm) { // throws BibliographicApiException {
		if (searchTerm == null || searchTerm.isEmpty())
			return Collections.emptyList();
		return this.buildQuery()
			.searchTerm(searchTerm)
			.execute()
			.get()
			.getIds()
			.collect(Collectors.toList());
	}

	@Deprecated
	public List<String> findPMIdsByDoi(String doi) { // throws BibliographicApiException {
		if (doi.isEmpty()) return Collections.emptyList();
		return this.buildQuery()
			.searchTerm(doi)
			.execute()
			.get()
			.getIds()
			.collect(Collectors.toList());
	}

	@Deprecated
	public List<String> findPMIdsByPubMedCentralIds(String pmcid) { // throws BibliographicApiException {
		if (pmcid.isEmpty()) return Collections.emptyList();
		return this.buildQuery()
			.searchTerm(pmcid.replace("PMC", ""))
			.execute()
			.get()
			.getIds()
			.collect(Collectors.toList());
	}

	private String keyFrom(String pmid) {
		var fetchParams = this.defaultApiParams();
		fetchParams.add("db", "pubmed");
		fetchParams.add("id", pmid);
		fetchParams.add("format", "xml");
		return keyFromApiQuery(EFETCH, fetchParams);
	}

	/**
	 * Fetch PubMed article metadata and abstract
	 * 
	 * @param pmids - the pubmed ids
	 * @return
	 * @throws BibliographicApiException
	 */
	public Set<EntrezEntry> getPMEntriesByPMIds(Collection<String> pmids) { // throws BibliographicApiException {
		Set<EntrezEntry> out = new HashSet<>();
		if (pmids.isEmpty()) return out;
		var cache = this.permanentCache();
		Collection<String> deferred = new HashSet<>();
		for (String pmid : pmids) {
			if (cache.containsKey(this.keyFrom(pmid))) {
				try {
					out.add(
						new EntrezEntry(
								Xml.fromStream(
									cache.get(this.keyFrom(pmid))
										.inputStream()
								)
									.content()
						)
					);
				} catch (XmlException e) {
					logger.debug("error parsing cached content for: " + pmid);
					cache.remove(this.keyFrom(pmid));
					deferred.add(pmid);
				}
			} else {
				deferred.add(pmid);
			}
		}
		if (!deferred.isEmpty()) {
			var fetchParams = this.defaultApiParams();
			fetchParams.add("db", "pubmed");
			deferred.forEach(id -> fetchParams.add("id", id));
			fetchParams.add("format", "xml");
			this.rateLimit(1);
			logger.debug(
				"making efetch query for {} pubmed records with params {}",
				deferred.size(),
				fetchParams.toString()
			);
			var is = this.buildCall(EFETCH, InputStream.class)
				.withParams(fetchParams)
				.withOperation(is2 -> is2)
				.post(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
			if (!is.isPresent()) {
				logger.warn(
					"Could not fetch content from entrez. Returning items from cache"
				);
				return out; // Fetch failed but there was cached content.
			}
			Xml xml;
			try {
				xml = Xml.fromStream(is.get());
				var tmp = new EntrezEntries(xml.content());
				tmp.stream()
					.forEach(entry -> {
						out.add(entry);
						var pmid = entry.getPMID()
							.get();
						cache.put(
							this.keyFrom(pmid),
							BinaryData.from(
								entry.getRaw()
									.outerXml()
							)
						);
					});
			} catch (XmlException e) {
				throw new BibliographicApiException("could not parse result", e);
			}
		}
		return out;
	}

	public Optional<EntrezEntries> getPMEntriesByWebEnvAndQueryKey(
			String webEnv, String queryKey,
			MultivaluedMap<String, String> fetchParams
	) {
		// MultivaluedMap<String, String> fetchParams = defaultApiParams();
		fetchParams.add("db", "pubmed");
		fetchParams.add("format", "xml");
		fetchParams.add("query_key", queryKey);
		fetchParams.add("WebEnv", webEnv);
		return this.buildCall(EFETCH, EntrezEntries.class)
			.withParams(fetchParams)
			.withOperation(is -> {
				var xml = Xml.fromStream(is);
				return new EntrezEntries(xml.content());
			})
			.post(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
	}

	public Optional<EntrezEntry> getPMEntryByPMId(String pmid) { // throws BibliographicApiException {
		if (pmid == null || pmid.isEmpty()) return Optional.empty();
		return this.getPMEntriesByPMIds(Collections.singletonList(pmid))
			.stream()
			.findFirst();
	}

	/**
	 * retrieves a full text for an article from PubMed Central
	 * 
	 * @param pmcId
	 * @return
	 */
	public Optional<InputStream> getPubMedCentralXMLByPubMedCentralId(
			String pmcId
	) {
		if (pmcId == null || pmcId.isEmpty()) return Optional.empty();
		return this.getXMLByIdsAndDatabase(
			Collections.singletonList(pmcId),
			Database.PMC
		);
	}

	public Optional<InputStream> getPubMedCentralXMLByPMEntries(
			EntrezEntries pmEntries
	) {
		List<String> pmcIds = pmEntries.stream()
			.flatMap(
				e -> e.getPMCID()
					.stream()
			)
			.collect(Collectors.toList());
		return this.getXMLByIdsAndDatabase(pmcIds, Database.PMC);
	}

	public Optional<InputStream> getPubMedCentralXMLByPMEntry(
			EntrezEntry pmEntry
	) {
		var pmcId = pmEntry.getPMCID();
		return pmcId.flatMap(this::getPubMedCentralXMLByPubMedCentralId);
	}

	public Optional<InputStream> getPubMedCentralPdfByPMEntry(
			EntrezEntry pmEntry, PdfFetcher pdfFetch
	) {
		if (pmEntry.getPdfUri()
			.isPresent()) {
			var pdfUrl = pmEntry.getPdfUri()
				.get()
				.toString();
			return pdfFetch.getPdfFromUrl(pdfUrl);
		} else
			return Optional.empty();
	}

	public Optional<InputStream> getPubMedCentralPdfByPMEntry(
			EntrezEntry pmEntry
	) throws BibliographicApiException {
		return this.getPubMedCentralPdfByPMEntry(pmEntry, PdfFetcher.create());
	}

	/**
	 * retrieves a full entries for a list of articles from the given database
	 * 
	 * @param ids - list of ids
	 * @param db  - the pubmed database
	 * @return
	 */
	public Optional<InputStream> getXMLByIdsAndDatabase(
			Collection<String> ids, Database db
	) {
		var params = this.defaultApiParams();
		params.add(
			"db",
			db.name()
				.toLowerCase()
		);
		params.add("retmode", "xml");
		params.add(
			"id",
			ids.stream()
				.collect(Collectors.joining(","))
		);
		logger.debug("making efetch query with params {}", params.toString());
		return this.buildCall(EFETCH, InputStream.class)
			.withParams(params)
			.withOperation(is -> is)
			.post(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
	}

	/**
	 * Elinks
	 * 
	 * @return
	 */
	public ELinksQueryBuilder buildLinksQueryForIdsAndDatabase(
			Collection<String> ids, Database fromDb
	) {
		return new ELinksQueryBuilder(this.defaultApiParams(), ids, fromDb, this);
	}

	/*
	 * The history mechanism doesn't really work due to a bug in Entrez which reformats the call slightly public
	 * ELinksQueryBuilder buildLinksQueryForSearchResult(Search search, Database fromDb) { return new
	 * ELinksQueryBuilder(defaultApiParams(), search.getWebEnv().get(), search.getQueryKey().get(),fromDb, this); }
	 */

	public static class ELinksQueryBuilder {
		MultivaluedMap<String, String> searchParams;
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd");
		boolean empty = false;
		EntrezClient client;

		@Deprecated
		protected WebResource get(WebResource searchService) {
			var tdmCopy = searchService;
			return tdmCopy.queryParams(this.searchParams);
		}

		protected <X> X post(WebResource searchService, Class<X> output) {
			var tdmCopy = searchService;
			return tdmCopy.post(output, this.searchParams);
		}

		// TODO: use putSingle instead of add
		protected ELinksQueryBuilder(
				MultivaluedMap<String, String> searchParams, Collection<String> ids,
				Database fromDb, EntrezClient client
		) {
			this.searchParams = searchParams;
			this.searchParams.add(
				"dbfrom",
				fromDb.name()
					.toLowerCase()
			);
			this.searchParams.add("cmd", "neighbour_score");
			this.searchParams.add("retmode", "xml");
			this.searchParams.add("retmax", "100000");
			ids.forEach(id -> this.searchParams.add("id", id));
			if (ids.isEmpty()) { this.empty = true; }
			this.client = client;
		}

		protected ELinksQueryBuilder(
				MultivaluedMap<String, String> searchParams, String webEnv,
				String queryKey, Database fromDb, EntrezClient client
		) {
			this.searchParams = searchParams;
			this.searchParams.add(
				"dbfrom",
				fromDb.name()
					.toLowerCase()
			);
			this.searchParams.add("cmd", "neighbour_score");
			this.searchParams.add("retmode", "xml");
			this.searchParams.add("WebEnv", webEnv);
			this.searchParams.add("retmax", "100000");
			this.searchParams.add("query_key", queryKey);
			this.client = client;
		}

		public ELinksQueryBuilder command(Command command) {
			this.searchParams.remove("cmd");
			this.searchParams.add(
				"cmd",
				command.name()
					.toLowerCase()
			);
			return this;
		}

		public ELinksQueryBuilder toDatabase(Database to) {
			this.searchParams.remove("db");
			this.searchParams.add(
				"db",
				to.name()
					.toLowerCase()
			);
			return this;
		}

		public ELinksQueryBuilder withLinkname(String linkName) {
			this.searchParams.putSingle("linkname", linkName);
			return this;
		}

		public ELinksQueryBuilder searchLinked(String term) {
			this.searchParams.putSingle("term", term);
			return this;
		}

		public ELinksQueryBuilder withinLastDays(int count) {
			this.searchParams.remove("reldate");
			this.searchParams.remove("datetype");
			this.searchParams.add("datetype", "edat");
			this.searchParams.add("reldate", Integer.toString(count));
			return this;
		}

		public ELinksQueryBuilder betweenDates(LocalDate start, LocalDate end) {
			this.searchParams.remove("mindate");
			this.searchParams.remove("maxdate");
			this.searchParams.remove("datetype");
			this.searchParams.add("datetype", "edat");
			this.searchParams.add("mindate", start.format(this.formatter));
			this.searchParams.add("maxdate", end.format(this.formatter));
			return this;
		}

		public Optional<EntrezLinks> execute() {
			if (this.empty) return Optional.empty();
			return this.client.buildCall(ELINK, EntrezLinks.class)
				.withParams(this.searchParams)
				.withOperation(is -> {
					var resp = Xml.fromStream(is);
					return new EntrezLinks(resp.content());
				})
				.post(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
		}

		@Override
		public String toString() {
			return this.searchParams.toString();
		}
	}

	public enum Command { NEIGHBOR, NEIGHBOR_SCORE, PRLINKS, LLINKS }

	/*
	 * public EntrezResult.Links link(ELinksQueryBuilder builder) throws BibliographicApiException {
	 * logger.debug("making elink query with params {}", builder.toString()); rateLimiter.consume(); InputStream is =
	 * builder.post(eLinkResource, InputStream.class); try {
	 * 
	 * Xml resp = Xml.fromStream(is); is.close(); return new EntrezResult.Links(resp.content());
	 * 
	 * } catch (XmlException | IOException e1) { throw new BibliographicApiException("could not parse result",e1); }
	 * 
	 * }
	 */

	public Map<String, Long> getSimilarScoredPMIdsByPMId(String pmid)
			throws BibliographicApiException {
		Map<String, Long> out = new HashMap<>();
		this
			.buildLinksQueryForIdsAndDatabase(
				Collections.singletonList(pmid),
				Database.PUBMED
			)
			.command(Command.NEIGHBOR_SCORE)
			.withLinkname("pubmed_pubmed")
			.execute()
			.stream()
			.flatMap(EntrezLinks::stream)
			.forEach(l -> {
				l.toId.ifPresent(to -> out.put(to, l.score.orElse(0L)));
			});
		return out;
	}

	public Map<String, String> getPubMedCentralIdsByPMId(
			Collection<String> pmids
	) throws BibliographicApiException {
		Map<String, String> out = new HashMap<>();
		this.buildLinksQueryForIdsAndDatabase(pmids, Database.PUBMED)
			.toDatabase(Database.PMC)
			.command(Command.NEIGHBOR)
			.withLinkname("pubmed_pmc")
			.execute()
			.stream()
			.flatMap(EntrezLinks::stream)
			.forEach(l -> l.toId.ifPresent(to -> out.put(l.fromId, to)));
		return out;
	}

	public Map<String, String> getReferencedPMIdsByPMId(Collection<String> pmids)
			throws BibliographicApiException {
		Map<String, String> out = new HashMap<>();
		this.buildLinksQueryForIdsAndDatabase(pmids, Database.PUBMED)
			.toDatabase(Database.PUBMED)
			.command(Command.NEIGHBOR)
			.withLinkname("pubmed_pubmed_refs")
			.execute()
			.stream()
			.flatMap(EntrezLinks::stream)
			.forEach(l -> l.toId.ifPresent(to -> out.put(l.fromId, to)));
		return out;
	}

	public List<String> getReferencingPubMedCentralIdsByPubMedCentralId(
			String pmcId
	) throws BibliographicApiException {
		pmcId = pmcId.replaceFirst("PMC", "");
		return this
			.buildLinksQueryForIdsAndDatabase(
				Collections.singletonList(pmcId),
				Database.PMC
			)
			.toDatabase(Database.PMC)
			.command(Command.NEIGHBOR)
			.withLinkname("pmc_pmc_citedby")
			.execute()
			.stream()
			.flatMap(EntrezLinks::stream)
			.flatMap(l -> l.toId.stream())
			.collect(Collectors.toList());
	}

	public List<String> getReferencedPubMedCentralIdsByPubMedCentralId(
			String pmcId
	) throws BibliographicApiException {
		pmcId = pmcId.replaceFirst("PMC", "");
		return this
			.buildLinksQueryForIdsAndDatabase(
				Collections.singletonList(pmcId),
				Database.PMC
			)
			.toDatabase(Database.PMC)
			.command(Command.NEIGHBOR)
			.withLinkname("pmc_pmc_cites")
			.execute()
			.stream()
			.flatMap(EntrezLinks::stream)
			.flatMap(l -> l.toId.stream())
			.collect(Collectors.toList());
	}

	@Override
	public Collection<? extends Record> search(Query qry) {
		var tmp = this.buildQuery()
			.withQuery(qry);
		List<EntrezEntry> out = new ArrayList<>();
		tmp.execute()
			.ifPresent(s -> {
				s.getStoredResult(this, tmp.searchParams)
					.ifPresent(e -> {
						out.addAll(
							e.stream()
								.collect(Collectors.toList())
						);
					});
			});
		return out;
	}

	@Override
	public Collection<? extends CitationLink> referencesCiting(
			Collection<? extends RecordReference> ref
	) {
		List<CitationLink> out = new ArrayList<>();

		Set<String> pmcids = this.idLookup.mappings(ref)
			.stream()
			.filter(
				rm -> rm.getTarget()
					.getIdentifierType()
					.equals(IdType.PMCID)
			)
			.flatMap(
				rm -> rm.getTarget()
					.getIdentifier()
					.stream()
			)
			.map(s -> s.replaceFirst("PMC", ""))
			.collect(Collectors.toSet());

		this.buildLinksQueryForIdsAndDatabase(pmcids, Database.PMC)
			.toDatabase(Database.PMC)
			.command(Command.NEIGHBOR)
			.withLinkname("pmc_pmc_citedby")
			.execute()
			.stream()
			.flatMap(l -> l.getCitations(true))
			.forEach(c -> out.add(c));
		return out;
	}

	@Override
	public Set<? extends CitationLink> citesReferences(
			Collection<? extends RecordReference> ref
	) {
		Set<CitationLink> out = new HashSet<>();

		var tmp = this.idLookup.mappings(ref);
		Set<String> pmcids = tmp.stream()
			.filter(
				rm -> rm.getTarget()
					.getIdentifierType()
					.equals(IdType.PMCID)
			)
			.flatMap(
				rm -> rm.getTarget()
					.getIdentifier()
					.stream()
			)
			.map(s -> s.replaceFirst("PMC", ""))
			.collect(Collectors.toSet());

		Set<String> pmids = tmp.stream()
			.filter(
				rm -> rm.getTarget()
					.getIdentifierType()
					.equals(IdType.PMID)
			)
			.flatMap(
				rm -> rm.getTarget()
					.getIdentifier()
					.stream()
			)
			.collect(Collectors.toSet());

		this.buildLinksQueryForIdsAndDatabase(pmids, Database.PUBMED)
			.toDatabase(Database.PUBMED)
			.command(Command.NEIGHBOR)
			.withLinkname("pubmed_pubmed_refs")
			.execute()
			.stream()
			.flatMap(l -> l.getCitations(false))
			.forEach(c -> out.add(c));

		this.buildLinksQueryForIdsAndDatabase(pmcids, Database.PMC)
			.toDatabase(Database.PMC)
			.command(Command.NEIGHBOR)
			.withLinkname("pmc_pmc_cites")
			.execute()
			.stream()
			.flatMap(l -> l.getCitations(false))
			.forEach(c -> out.add(c));
		return out;
	}

	@Override
	public Map<ImmutableRecordReference, EntrezEntry> fetch(
			Collection<? extends RecordReference> ref
	) {
		Map<ImmutableRecordReference, EntrezEntry> out = new HashMap<>();

		Set<String> pmids = this.idLookup.mappings(ref)
			.stream()
			.filter(
				rm -> rm.getTarget()
					.getIdentifierType()
					.equals(IdType.PMID)
			)
			.flatMap(
				rm -> rm.getTarget()
					.getIdentifier()
					.stream()
			)
			.map(s -> s.replaceFirst("PMC", ""))
			.collect(Collectors.toSet());

		this.getPMEntriesByPMIds(pmids)
			.forEach(ee -> out.put(Builder.recordReference(ee), ee));
		return out;
	}

	@Override
	public Set<? extends RecordReferenceMapping> mappings(
			Collection<? extends RecordReference> source
	) {
		Set<RecordReferenceMapping> out = new HashSet<>();
		var tmp = this.fetch(source);
		for (Entry<ImmutableRecordReference, EntrezEntry> e : tmp.entrySet()) {
			Set<ImmutableRecordReference> allIds = new HashSet<>();
			allIds.add(e.getKey());
			allIds.add(Builder.recordReference(e.getValue()));
			e.getValue()
				.getOtherIdentifiers()
				.stream()
				.map(Builder::recordReference)
				.forEach(allIds::add);
			for (ImmutableRecordReference src : allIds) {
				for (ImmutableRecordReference targ : allIds) {
					// if (!src.equals(targ)) {
					out.add(Builder.recordReferenceMapping(src, targ));
					// }
				}
			}
		}
		return out;
	}

}