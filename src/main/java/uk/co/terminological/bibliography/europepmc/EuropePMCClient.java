package uk.co.terminological.bibliography.europepmc;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

import uk.co.terminological.bibliography.BibliographicApiException;
import uk.co.terminological.bibliography.CachingApiClient;
import uk.co.terminological.bibliography.client.CitedByMapper;
import uk.co.terminological.bibliography.client.CitesMapper;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.client.Searcher;
import uk.co.terminological.bibliography.europepmc.EuropePMCListResult.Citation;
import uk.co.terminological.bibliography.europepmc.EuropePMCListResult.Reference;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableCitationLink;
import uk.co.terminological.bibliography.record.ImmutableCitationReference;
import uk.co.terminological.bibliography.record.ImmutablePrintReference;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.PrintReference;
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

	public QueryBuilder buildQuery(String searchTerm) {
		return new QueryBuilder(this.defaultApiParams(), searchTerm, this);
	}

	public static class QueryBuilder {
		MultivaluedMap<String, String> searchParams;
		EuropePMCClient client;
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

		protected QueryBuilder(
				MultivaluedMap<String, String> searchParams, String searchTerm,
				EuropePMCClient client
		) {
			this.searchParams = searchParams;
			this.searchParams.add("query", searchTerm);
			this.client = client;
		}

		public QueryBuilder useSynonyms() {
			this.searchParams.add("synonym", "true");
			return this;
		}

		public QueryBuilder withSort(Field field, Direction dir) {
			this.searchParams.add("sort", field.toString() + " " + dir.toString());
			return this;
		}

		public Optional<EuropePMCListResult<EuropePMCLiteResult>> executeLite() {
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

		public Optional<EuropePMCListResult<EuropePMCCoreResult>> executeFull() {
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
			var fromStr = from.map(d -> d.format(this.formatter))
				.orElse("1900-01-01");
			var toStr = to.orElse(LocalDate.now())
				.format(this.formatter);
			var search = this.searchParams.get("query")
				.stream()
				.collect(Collectors.joining(" "));
			this.searchParams.putSingle(
				"query",
				search + " AND (FIRST_PDATE:[" + fromStr + " TO " + toStr + "])"
			);
			return this;
		}

		public QueryBuilder limit(Optional<Integer> records) {
			records.ifPresent(
				r -> this.searchParams.putSingle("pageSize", r.toString())
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
		return this.buildQuery(text)
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
		return this.buildQuery(text)
			.executeFull()
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
			Collection<? extends RecordReference> ref
	) {

		Set<CitationLink> out = new HashSet<>();

		for (RecordReference r : ref) {

			String title = null;
			ImmutablePrintReference print = null;
			if (r instanceof Record) {
				title = ((Record) r).getTitle()
					.orElse(null);
			}
			if (r instanceof PrintReference) {
				print = Builder.printReference((PrintReference) r);
			}

			if (r.getIdentifierType()
				.equals(IdType.PMID)) {
				var i = 0;
				for (EuropePMCReference r2 : this
					.references(
						DataSources.MED,
						r.getIdentifier()
							.get()
					)
					.getItems()
					.collect(Collectors.toList())) {
					out.add(
						new ImmutableCitationLink(
								new ImmutableCitationReference(r, title, print),
								new ImmutableCitationReference(
										r2, r2.getTitle()
											.orElse(null),
										null
								), Optional.of(i++)
						)
					);
				}
			}

			if (r.getIdentifierType()
				.equals(IdType.DOI)) {
				var i = 0;
				for (EuropePMCReference r2 : this.referencesForDoi(
					r.getIdentifier()
						.get()
				)) {
					out.add(
						new ImmutableCitationLink(
								new ImmutableCitationReference(r, title, print),
								new ImmutableCitationReference(
										r2, r2.getTitle()
											.orElse(null),
										null
								), Optional.of(i++)
						)
					);
				}
			}
			if (r.getIdentifierType()
				.equals(IdType.PMCID)) {
				var i = 0;
				for (EuropePMCReference r2 : this
					.references(
						DataSources.PMC,
						r.getIdentifier()
							.get()
					)
					.getItems()
					.collect(Collectors.toList())) {
					out.add(
						new ImmutableCitationLink(
								new ImmutableCitationReference(r, title, print),
								new ImmutableCitationReference(
										r2, r2.getTitle()
											.orElse(null),
										null
								), Optional.of(i++)
						)
					);
				}
			}
		}

		return out;

	}

	@Override
	public Collection<? extends CitationLink> referencesCiting(
			Collection<? extends RecordReference> ref
	) {

		Set<CitationLink> out = new HashSet<>();

		for (RecordReference r : ref) {

			String title = null;
			ImmutablePrintReference print = null;
			if (r instanceof Record) {
				title = ((Record) r).getTitle()
					.orElse(null);
			}
			if (r instanceof PrintReference) {
				print = Builder.printReference((PrintReference) r);
			}

			if (r.getIdentifierType()
				.equals(IdType.PMID)) {
				var i = 0;
				for (EuropePMCCitation r2 : this
					.citations(
						DataSources.MED,
						r.getIdentifier()
							.get()
					)
					.getItems()
					.collect(Collectors.toList())) {
					out.add(
						new ImmutableCitationLink(
								new ImmutableCitationReference(
										r2, r2.getTitle()
											.orElse(null),
										null
								), new ImmutableCitationReference(r, title, print),
								Optional.of(i++)
						)
					);
				}
			}

			if (r.getIdentifierType()
				.equals(IdType.DOI)) {
				var i = 0;
				for (EuropePMCCitation r2 : this.citationsForDoi(
					r.getIdentifier()
						.get()
				)) {
					out.add(
						new ImmutableCitationLink(
								new ImmutableCitationReference(
										r2, r2.getTitle()
											.orElse(null),
										null
								), new ImmutableCitationReference(r, title, print),
								Optional.of(i++)
						)
					);
				}
			}
			if (r.getIdentifierType()
				.equals(IdType.PMCID)) {
				var i = 0;
				for (EuropePMCCitation r2 : this
					.citations(
						DataSources.PMC,
						r.getIdentifier()
							.get()
					)
					.getItems()
					.collect(Collectors.toList())) {
					out.add(
						new ImmutableCitationLink(
								new ImmutableCitationReference(
										r2, r2.getTitle()
											.orElse(null),
										null
								), new ImmutableCitationReference(r, title, print),
								Optional.of(i++)
						)
					);
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
	public Collection<? extends Record> search(
			String search, Optional<LocalDate> from, Optional<LocalDate> to,
			Optional<Integer> limit
	) {
		return this.buildQuery(search)
			.between(from, to)
			.limit(limit)
			.executeFull()
			.stream()
			.flatMap(EuropePMCListResult::getItems)
			.collect(Collectors.toList());

	}

}
