package uk.co.terminological.bibliography.api.europepmc;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.isomorphism.util.TokenBuckets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import uk.co.terminological.bibliography.CachingApiClient;
import uk.co.terminological.bibliography.api.BibliographicApiException;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCListResult.Citation;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCListResult.Reference;
import uk.co.terminological.bibliography.client.CitedByMapper;
import uk.co.terminological.bibliography.client.CitesMapper;
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
import uk.co.terminological.datatypes.Tuple;

public class EuropePMCClient extends CachingApiClient
		implements Searcher, RecordFetcher, CitesMapper, CitedByMapper {

	// https://europepmc.org/RestfulWebService

	// ####### Constructors / factories etc ####### //

	// private static Logger log = LoggerFactory.getLogger(EuropePmcClient.class);

	private EuropePMCClient(Optional<Path> optional, String developerEmail) {
		super(
				optional, TokenBuckets.builder()
					.withCapacity(50)
					.withInitialTokens(50)
					.withFixedIntervalRefillStrategy(50, 1, TimeUnit.SECONDS)
					.build()
		);
		this.developerEmail = developerEmail;
	}

	private static String baseUrl = "https://www.ebi.ac.uk/europepmc/webservices/rest/"; // search?query=malaria&format=json

	private String developerEmail;
	private ObjectMapper objectMapper = new ObjectMapper();

	@Override
	protected MultivaluedMap<String, String> defaultApiParams() {
		MultivaluedMap<String, String> out = new MultivaluedMapImpl();
		out.add("format", "json");
		out.add("pageSize", "1000");
		if (this.developerEmail != null) {
			out.add("email", this.developerEmail);
		}
		return out;
	}

	private static EuropePMCClient singleton;

	public static EuropePMCClient create(String developerEmail) {
		return create(developerEmail, null);
	}

	public static EuropePMCClient create(String developerEmail, Path cacheDir) {
		if (singleton == null) {
			singleton = new EuropePMCClient(
					Optional.ofNullable(cacheDir), developerEmail
			);
		}
		return singleton;
	}

	// ####### API methods ####### //

	/**
	 * Refuse a response that cannot be a complete Europe PMC list. Throwing here means CachingApiClient treats the call as
	 * failed: it is not cached and the next call retries, instead of a truncated body becoming a lasting "not found"
	 * (issue-004).
	 */
	private static <X> X complete(X parsed) {
		if (parsed instanceof EuropePMCListResult
				&& !((EuropePMCListResult<?>) parsed).isWellFormed())
			throw new BibliographicApiException(
					"incomplete Europe PMC response: no hitCount or result collection"
			);
		return parsed;
	}

	public Optional<EuropePMCCoreResult> getById(String id, IdType type) {
		EuropePMCListResult<EuropePMCCoreResult> tmp;
		if (type.equals(IdType.DOI)) {
			tmp = this.fullSearch("DOI:" + id);
		} else if (type.equals(IdType.PMCID)) {
			tmp = this.fullSearch("PMCID:PMC" + id);
		} else if (type.equals(IdType.PMID)) {
			// EXT_ID:16199517
			tmp = this.fullSearch("EXT_ID:" + id + " AND SRC:MED");
		} else {
			tmp = this.fullSearch("EXT_ID:" + id);
		}
		return tmp.getItems()

			.findFirst();
	}

	@Override
	public QueryBuilder buildQuery() {
		return new QueryBuilder(this);
	}

	protected enum SearchFields {
		JOURNAL(
				null// This is deliberately set to a full text search field as otherwise
		// EPM needs an exact match on the journal short title
		), AUTHOR(
				"AUTH"
		), TITLE_ABSTRACT(
				"TITLE_ABS"
		), TEXT(
				null
		), ALL(
				null
		);

		private String marker;
		SearchFields(String m) {
			this.marker = m;
		}
		public String searchTerm(String value) {
			if (this.marker == null) return "(" + value + ")";
			return this.marker + ":(" + value + ")";
		}
	}

	public static class QueryBuilder
			implements SearchBuilder<EuropePMCListResult<EuropePMCCoreResult>> {
		MultivaluedMap<String, String> searchParams;
		EuropePMCClient client;
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
		List<String> terms = new ArrayList<>();

		protected QueryBuilder(EuropePMCClient client) {
			this.searchParams = client.defaultApiParams();
			this.client = client;
		}

		public QueryBuilder searchTerm(String searchTerm) {
			if (searchTerm != null && searchTerm.length() > 0) {
				this.terms.add(searchTerm);
			}
			return this;
		}

		@Override
		public QueryBuilder withQuery(Query qry) {
			qry.getNativeQuery()
				.ifPresent(this.terms::add);
			qry.getAllFields()
				.stream()
				.map(SearchFields.ALL::searchTerm)
				.forEach(this.terms::add);
			qry.getTitleAbstract()
				.stream()
				.map(SearchFields.TITLE_ABSTRACT::searchTerm)
				.forEach(this.terms::add);
			qry.getFullText()
				.stream()
				.map(SearchFields.TEXT::searchTerm)
				.forEach(this.terms::add);
			qry.getAuthors()
				.stream()
				.map(SearchFields.AUTHOR::searchTerm)
				.forEach(this.terms::add);
			qry.getJournal()
				.stream()
				.map(SearchFields.JOURNAL::searchTerm)
				.forEach(this.terms::add);
			this.between(qry.getAfter(), qry.getUntil());
			this.limit(qry.getLimit());
			return this;
		}

		public QueryBuilder useSynonyms() {
			this.searchParams.add("synonym", "true");
			return this;
		}

		public QueryBuilder withSort(Field field, Direction dir) {
			this.searchParams.add("sort", field.toString() + " " + dir.toString());
			return this;
		}

		/**
		 * The {@code query} this builder would send, assembled exactly as {@link #execute()} assembles it.
		 *
		 * Exposed so the translation from a {@link Query} can be asserted without a network call: the native syntax is the part
		 * most likely to be wrong, and it is otherwise only observable by making the request.
		 */
		public String query() {
			return this.terms.stream()
				.collect(Collectors.joining(" AND "));
		}

		/** The request parameters as they stand, for the same reason as {@link #query()}. Live, not a copy. */
		public MultivaluedMap<String, String> params() {
			return this.searchParams;
		}

		public Optional<EuropePMCListResult<EuropePMCLiteResult>> executeLite() {
			if (this.terms.size() == 0) return Optional.empty();
			this.searchParams.add("query", this.query());
			this.searchParams.add("resultType", "lite");
			return this.client
				.buildCall(baseUrl + "searchPost", EuropePMCListResult.Lite.class)
				.withParams(this.searchParams)
				.withOperation(
					is -> complete(
						new EuropePMCListResult.Lite(
								this.client.objectMapper.readTree(is)
						)
					)
				)
				.post(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
				.map(o -> (EuropePMCListResult<EuropePMCLiteResult>) o);

		}

		@Override
		public Optional<EuropePMCListResult<EuropePMCCoreResult>> execute() {
			if (this.terms.size() == 0) return Optional.empty();
			this.searchParams.add("query", this.query());
			this.searchParams.add("resultType", "core");
			return this.client
				.buildCall(baseUrl + "search", EuropePMCListResult.Core.class)
				// return client.buildCall(baseUrl+"searchPOST", EuropePMCListResult.Core.class)
				.withParams(this.searchParams)
				.withOperation(
					is -> complete(
						new EuropePMCListResult.Core(
								this.client.objectMapper.readTree(is)
						)
					)
					// ).post();
				)
				.get()
				.map(o -> (EuropePMCListResult<EuropePMCCoreResult>) o);
		}

		public QueryBuilder between(
				Optional<LocalDate> from, Optional<LocalDate> to
		) {
			if (from.isPresent() || to.isPresent()) {
				var fromStr = from.map(d -> d.format(this.formatter))
					.orElse("1900-01-01");
				var toStr = to.orElse(LocalDate.now())
					.format(this.formatter);
				this.terms.add("(FIRST_PDATE:[" + fromStr + " TO " + toStr + "])");
			}
			return this;
		}

		public QueryBuilder limit(Optional<Integer> records) {
			records.ifPresent(
				r -> this.searchParams
					.putSingle("pageSize", Integer.toString(r > 1000 ? 1000 : r))
			);
			return this;
		}

	}

	public enum Field { P_PDATE_D, AUTH_FIRST, CITED }

	public enum Direction {
		ASC, DESC;
		@Override
		public String toString() {
			return this.name()
				.toLowerCase();
		}
	}

	public EuropePMCListResult<EuropePMCLiteResult> liteSearch(String text) {
		// https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=DOI:10.1038/nature09534&sort=CITED%20desc&format=json
		// https://www.ebi.ac.uk/europepmc/webservices/rest/searchPOST
		// query=malaria%20sort_cited:y
		// format=json
		// sort=CITED desc (P_PDATE_D, AUTH_FIRST, CITED (see https://www.ebi.ac.uk/europepmc/webservices/rest/fields))
		// pageSize=1000 (max)
		// cursorMark=* (paging...)
		// synonym=false/true
		// resultType=idlist/lite/core
		// POST
		return this.buildQuery()
			.searchTerm(text)
			.executeLite()
			.orElse(EuropePMCListResult.empty(EuropePMCLiteResult.class));
	}

	public EuropePMCListResult<EuropePMCCoreResult> fullSearch(String text) {
		// https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=DOI:10.1038/nature09534&sort=CITED%20desc&format=json
		// https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=PMC:PMC6560460&sort=CITED%20desc&format=json
		// https://www.ebi.ac.uk/europepmc/webservices/rest/searchPOST
		// query=malaria%20sort_cited:y
		// format=json
		// sort=CITED desc (P_PDATE_D, AUTH_FIRST, CITED (see https://www.ebi.ac.uk/europepmc/webservices/rest/fields))
		// pageSize=1000 (max)
		// cursorMark=* (paging...)
		// synonym=false/true
		// resultType=idlist/lite/core
		// POST
		return this.buildQuery()
			.searchTerm(text)
			.execute()
			.orElse(EuropePMCListResult.empty(EuropePMCCoreResult.class));
	}

	public EuropePMCListResult<EuropePMCCitation> citations(
			DataSources source, String id
	) {
		if (source.equals(DataSources.PMC)) {
			id = "PMC" + id.replaceAll("^PMC", "");
		}
		// https://www.ebi.ac.uk/europepmc/webservices/rest/MED/9843981/citations?format=json
		// id=23245604
		// source=MED
		// page=0
		// pageSize=1000 (max)
		// format=json
		return this
			.buildCall(
				baseUrl + source + "/" + id + "/citations",
				EuropePMCListResult.Citation.class
			)
			.withParams(this.defaultApiParams())
			.withOperation(
				is -> complete(
					new EuropePMCListResult.Citation(this.objectMapper.readTree(is))
				)
			)
			.get()
			.orElseGet(Citation::empty);
	}

	public EuropePMCListResult<EuropePMCReference> references(
			DataSources source, String id
	) {
		// https://www.ebi.ac.uk/europepmc/webservices/rest/PMC/PMC6560460/references?format=json
		if (source.equals(DataSources.PMC)) {
			id = "PMC" + id.replaceAll("^PMC", "");
		}
		// https://www.ebi.ac.uk/europepmc/webservices/rest/MED/9843981/references?format=json
		// id=23245604
		// source=MED
		// page=0
		// pageSize=1000 (max)
		// format=json
		return this
			.buildCall(
				baseUrl + source + "/" + id + "/references",
				EuropePMCListResult.Reference.class
			)
			.withParams(this.defaultApiParams())
			.withOperation(
				is -> complete(
					new EuropePMCListResult.Reference(this.objectMapper.readTree(is))
				)
			)
			.get()
			.orElseGet(Reference::empty);
	}

	public enum DataSources { AGR, CBA, CTX, ETH, HIR, MED, NBK, PAT, PMC, PPR }

	/**
	 * The references of a work known by DOI.
	 *
	 * Europe PMC indexes preprints as `PPR` and journal articles as `MED`, so a DOI has to be resolved to EPMC's own source
	 * and id before its reference list can be asked for. A DOI reference used to be ignored entirely, which made a
	 * preprint's references look absent even though the API held them.
	 */
	private List<EuropePMCReference> referencesForDoi(String doi) {
		return this.resolveDoi(doi)
			.map(
				resolved -> this
					.references(resolved.getFirst(), resolved.getSecond())
					.getItems()
					.collect(Collectors.toList())
			)
			.orElseGet(List::of);
	}

	private List<EuropePMCCitation> citationsForDoi(String doi) {
		return this.resolveDoi(doi)
			.map(
				resolved -> this
					.citations(resolved.getFirst(), resolved.getSecond())
					.getItems()
					.collect(Collectors.toList())
			)
			.orElseGet(List::of);
	}

	/** EPMC's own source and id for a work known by DOI, or empty if it has neither */
	private Optional<Tuple<DataSources, String>> resolveDoi(String doi) {
		return this.fullSearch("DOI:" + doi)
			.getItems()
			.findFirst()
			.flatMap(
				item -> item.getSource()
					.flatMap(
						source -> item.asString("id")
							.map(id -> Tuple.create(source, id))
					)
			);
	}

	/**
	 * Whether EPMC knows this DOI at all, and under which source. Exposed because the difference between "no references"
	 * and "not indexed here" matters to a caller.
	 */
	public Optional<String> resolveDoiSource(String doi) {
		return this.resolveDoi(doi)
			.map(
				resolved -> resolved.getFirst()
					.toString()
			);
	}

	@Override
	public Collection<? extends CitationLink> citesReferences(
			Collection<? extends RecordReference> sources
	) {

		// The source cites a target
		// The relationship is source --> target

		Set<CitationLink> out = new HashSet<>();

		for (RecordReference source : sources) {

			if (source.getIdentifierType()
				.equals(IdType.PMID)) {
				var i = 0;
				for (EuropePMCReference target : this
					.references(
						DataSources.MED,
						source.getIdentifier()
							.get()
					)
					.getItems()
					.collect(Collectors.toList())) {
					out.add(Builder.citationLink(source, target, i));
				}
			}

			if (source.getIdentifierType()
				.equals(IdType.DOI)) {
				var i = 0;
				for (EuropePMCReference target : this.referencesForDoi(
					source.getIdentifier()
						.get()
				)) {
					out.add(Builder.citationLink(source, target, i));
				}
			}
			if (source.getIdentifierType()
				.equals(IdType.PMCID)) {
				var i = 0;
				for (EuropePMCReference target : this
					.references(
						DataSources.PMC,
						source.getIdentifier()
							.get()
					)
					.getItems()
					.collect(Collectors.toList())) {
					out.add(Builder.citationLink(source, target, i));
				}
			}
		}

		return out;

	}

	@Override
	public Collection<? extends CitationLink> referencesCiting(
			Collection<? extends RecordReference> sources
	) {

		// The articles (target) that site this source
		// The link should be target --> source

		Set<CitationLink> out = new HashSet<>();

		for (RecordReference source : sources) {

			if (source.getIdentifierType()
				.equals(IdType.PMID)) {
				var i = 0;
				for (EuropePMCCitation target : this
					.citations(
						DataSources.MED,
						source.getIdentifier()
							.get()
					)
					.getItems()
					.collect(Collectors.toList())) {
					out.add(Builder.citationLink(target, source, i));
				}
			}

			if (source.getIdentifierType()
				.equals(IdType.DOI)) {
				var i = 0;
				for (EuropePMCCitation target : this.citationsForDoi(
					source.getIdentifier()
						.get()
				)) {
					out.add(Builder.citationLink(target, source, i));
				}
			}
			if (source.getIdentifierType()
				.equals(IdType.PMCID)) {
				var i = 0;
				for (EuropePMCCitation target : this
					.citations(
						DataSources.PMC,
						source.getIdentifier()
							.get()
					)
					.getItems()
					.collect(Collectors.toList())) {
					out.add(Builder.citationLink(target, source, i));
				}
			}
		}

		return out;
	}

	@Override
	public Map<ImmutableRecordReference, ? extends Record> fetch(
			Collection<? extends RecordReference> equivalentIds
	) {
		Map<ImmutableRecordReference, Record> out = new HashMap<>();

		for (RecordReference rr : equivalentIds) {
			if (rr.getIdentifier()
				.isPresent()) {
				this.getById(
					rr.getIdentifier()
						.get(),
					rr.getIdentifierType()
				)
					.ifPresent(r -> out.put(Builder.recordReference(rr), r));
			}
		}

		return out;
	}

	@Override
	public Collection<EuropePMCCoreResult> search(Query qry) {
		return this.buildQuery()
			.withQuery(qry)
			.execute()
			.stream()
			.flatMap(EuropePMCListResult::getItems)
			.collect(Collectors.toList());

	}

}
