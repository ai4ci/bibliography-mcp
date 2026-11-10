package uk.co.terminological.bibliography.api.openalex;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.client.StreamExceptions;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.PrintReference;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.PublicationTypes;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordWithCitations;

/**
 * One {@code works[]} entry: everything OpenAlex asserts about one work.
 *
 * The interesting shape difference from the other sources is that OpenAlex nests what everyone else keeps flat. Journal
 * metadata lives under {@code primary_location.source} (the same trap that produced issue-012 for Europe PMC core),
 * identifiers are URLs rather than ids, and the PMC id is the URL slug without its {@code PMC} prefix.
 *
 * [IMPLEMENTS](/design/features/feat-006-classify-records-by-type-with-provenance.md)
 */
public class OpenAlexRecord extends ExtensibleJson
		implements PrintReference, RecordWithCitations {

	public OpenAlexRecord(JsonNode node) {
		super(node);
	}

	/**
	 * The OpenAlex id, which is the tail of a URL: {@code https://openalex.org/W7140411570} names {@code W7140411570}.
	 *
	 * The top-level {@code id} is read as well as {@code ids.openalex} because they always carry the same value, and a work
	 * whose {@code ids} block is missing is still a work with a name.
	 */
	private Optional<String> openAlexId() {
		return Stream
			.concat(this.streamPath("ids", "openalex"), this.streamPath("id"))
			.map(ExtensibleJson::asString)
			.filter(s -> !s.isEmpty())
			.findFirst()
			.map(s -> s.substring(s.lastIndexOf('/') + 1));
	}

	@Override
	public Optional<String> getIdentifier() {
		// A DOI names a work across every other registered API, so it is the identity
		// here; the OpenAlex id is a handle within one vendor's graph and is offered as
		// an alternate in getOtherIdentifiers() instead.
		return this.getDOI()
			.or(this::openAlexId);
	}

	@Override
	public IdType getIdentifierType() {
		return this.getDOI()
			.isPresent() ? IdType.DOI
				: this.openAlexId()
					.isPresent() ? IdType.OPENALEX : IdType.UNK;
	}

	private Optional<String> getDOI() {
		return this.streamPath("ids", "doi")
			.map(ExtensibleJson::asString)
			.findFirst()
			// Published as a resolver URL; every other client in this project stores
			// the bare suffix, and identifiers are how records from different APIs are
			// recognised as one work
			.map(s -> s.replaceFirst("^https?://(dx\\.)?doi\\.org/", ""));
	}

	private Optional<String> getPMID() {
		return this.streamPath("ids", "pmid")
			.map(ExtensibleJson::asString)
			.findFirst()
			.map(s -> s.substring(s.lastIndexOf('/') + 1));
	}

	/**
	 * OpenAlex stores the PubMed Central id as a bare URL slug ({@code .../articles/13141338}) while idconv, PubMed and
	 * Europe PMC all store it as {@code PMC13141338}. The prefix is restored here because identifiers are how records from
	 * different APIs are recognised as one work: un-normalised, the same paper reaches {@code MergedRecord} twice.
	 */
	private Optional<String> getPMCID() {
		return this.streamPath("ids", "pmcid")
			.map(ExtensibleJson::asString)
			.findFirst()
			.map(s -> s.substring(s.lastIndexOf('/') + 1))
			.filter(s -> !s.isEmpty())
			.map(s -> s.startsWith("PMC") ? s : "PMC" + s);
	}

	@Override
	public List<RecordReference> getOtherIdentifiers() {
		List<RecordReference> tmp = new ArrayList<>();
		this.getDOI()
			.map(d -> Builder.recordReference(IdType.DOI, d))
			.ifPresent(tmp::add);
		this.getPMID()
			.map(d -> Builder.recordReference(IdType.PMID, d))
			.ifPresent(tmp::add);
		this.getPMCID()
			.map(d -> Builder.recordReference(IdType.PMCID, d))
			.ifPresent(tmp::add);
		this.openAlexId()
			.map(d -> Builder.recordReference(IdType.OPENALEX, d))
			.ifPresent(tmp::add);
		return tmp;
	}

	@Override
	public List<Author> getAuthors() {
		return this.streamPath(OpenAlexAuthor.class, "authorships")
			.collect(Collectors.toList());
	}

	@Override
	public Provenance getProvenance() {
		return Provenance.canonical(Provenance.OPENALEX, this.asString("type"));
	}

	@Override
	public Optional<String> getPublicationType() {
		return this.asString("type")
			.map(
				t -> PublicationTypes.fromOpenAlex(t)
					.orElse(t)
			);
	}

	/** The host organisation of the container: a journal's publisher, a repository's operator. */
	@Override
	public Optional<String> getPublisher() {
		return this.sourceField("host_organization_name");
	}

	@Override
	public Optional<String> getTitle() { return this.asString("title"); }

	/**
	 * Journal for a published work, repository name for a preprint.
	 *
	 * For a preprint there is no container, but {@code MergePolicy} treats posted content as owned by its preprint server,
	 * so the server name has to be reachable through this accessor or that rule has nothing to compare.
	 */
	@Override
	public Optional<String> getJournal() {
		return this.sourceField("display_name");
	}

	private Optional<String> sourceField(String field) {
		return this.streamPath("primary_location", "source", field)
			.map(ExtensibleJson::asString)
			.findFirst();
	}

	@Override
	public Optional<String> getVolume() {
		return this.streamPath("biblio", "volume")
			.map(ExtensibleJson::asString)
			.findFirst();
	}

	@Override
	public Optional<String> getIssue() {
		return this.streamPath("biblio", "issue")
			.map(ExtensibleJson::asString)
			.findFirst();
	}

	@Override
	public Optional<String> getPage() {
		return this.streamPath("biblio", "first_page")
			.map(ExtensibleJson::asString)
			.findFirst();
	}

	@Override
	public Optional<Long> getYear() { return this.asLong("publication_year"); }

	@Override
	public Optional<LocalDate> getDate() {
		return this.asString("publication_date")
			.flatMap(StreamExceptions.ignoreFunction(LocalDate::parse));
	}

	@Override
	public Optional<String> getFirstAuthorName() {
		return this.getFirstAuthor()
			.map(Author::getLastName);
	}

	@Override
	public Stream<String> getLicenses() {
		return Stream.concat(
			this.streamPath("locations", "license")
				.map(ExtensibleJson::asString),
			this.streamPath("locations", "license_id")
				.map(ExtensibleJson::asString)
		)
			.filter(s -> s != null)
			.distinct();
	}

	/**
	 * The best open-access pdf, whichever location it came from.
	 *
	 * This is the field {@code MergePolicy} ranks the PDF priority on, and it is why OpenAlex replaces Unpaywall: the
	 * answer is in the same payload as the metadata, so locating an open copy costs no second API call.
	 */
	@Override
	public Optional<URI> getPdfUri() {
		return Stream
			.concat(
				this.streamPath("best_oa_location", "pdf_url"),
				this.streamPath("primary_location", "pdf_url")
			)
			.map(ExtensibleJson::asString)
			.findFirst()
			.flatMap(StreamExceptions.ignoreRuntime(URI::create));
	}

	/**
	 * The abstract, reassembled from OpenAlex's inverted index.
	 *
	 * The payload gives word to positions, so joining the keys in map order reads as fluent nonsense rather than failing.
	 * The words are placed by position, which also restores repeats: this corpus uses the same word in several slots (179
	 * distinct words fill 321 positions), and a word that appears twice must appear twice here.
	 */
	@Override
	public Optional<String> getAbstract() {
		var index = this.getRaw()
			.get("abstract_inverted_index");
		if (index == null || !index.isObject() || index.size() == 0)
			return Optional.empty();

		var words = new TreeMap<Integer, String>();
		index.fields()
			.forEachRemaining(e -> {
				for (JsonNode position : e.getValue()) {
					words.put(position.asInt(), e.getKey());
				}
			});
		if (words.isEmpty()) return Optional.empty();
		return Optional.of(String.join(" ", words.values()));
	}

	/** True when OpenAlex flags the work as retracted. */
	public boolean isRetracted() {
		return this.getRaw()
			.path("is_retracted")
			.asBoolean(false);
	}

	public Optional<String> getOAStatus() {
		return this.streamPath("open_access", "oa_status")
			.map(ExtensibleJson::asString)
			.findFirst();
	}

	/** The works this work cites, as OpenAlex ids. These are graph ids, not DOIs: resolving them needs a batch fetch. */
	public List<RecordReference> getReferencedWorks() {
		return this.streamPath("referenced_works")
			.map(ExtensibleJson::asString)
			.map(OpenAlexClient::workReference)
			.collect(Collectors.toList());
	}

	/**
	 * This work's own reference list, as edges pointing away from it.
	 *
	 * The referenced works come back as OpenAlex ids only, so an edge's target is a node in OpenAlex's graph rather than a
	 * work the caller can name: {@code OpenAlexClient.citesReferences} resolves the batch to DOIs and PMIDs so the edges
	 * join the ones the other APIs produce.
	 */
	@Override
	public List<CitationLink> getCitations() {
		List<CitationLink> tmp = new ArrayList<>();
		Integer i = 1;
		for (RecordReference ref : (Iterable<RecordReference>) this
			.getReferencedWorks()::iterator) {
			tmp.add(Builder.citationLink(this, ref, i));
			i += 1;
		}
		return tmp;
	}

	@Override
	public Optional<Long> getCitedByCount() {
		return this.asLong("cited_by_count");
	}

	@Override
	public Optional<Long> getReferencesCount() {
		return this.asLong("referenced_works_count");
	}

}
