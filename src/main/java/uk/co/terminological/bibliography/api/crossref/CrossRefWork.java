package uk.co.terminological.bibliography.api.crossref;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableCitationLink;
import uk.co.terminological.bibliography.record.PlainText;
import uk.co.terminological.bibliography.record.PrintReference;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.PublicationTypes;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordWithCitations;
import uk.co.terminological.datatypes.StreamExceptions;

// [IMPLEMENTS](/design/features/feat-006-classify-records-by-type-with-provenance.md)
// [IMPLEMENTS](/design/features/feat-008-clean-up-collection-metadata.md)
public class CrossRefWork extends ExtensibleJson
		implements PrintReference, RecordWithCitations {
	public CrossRefWork(JsonNode node) {
		super(node);
	}

	@Override
	public Optional<String> getIdentifier() {
		return this.asString("DOI")
			.map(s -> s.replaceAll("^PMC", ""));
	}
	@Override
	public IdType getIdentifierType() { return IdType.DOI; }
	// CrossRef's vocabulary is close to CSL but not identical (journal-article vs
	// article-journal); an unmapped value is passed through unchanged.
	@Override
	public Optional<String> getPublicationType() {
		return this.asString("type")
			.map(
				t -> PublicationTypes.fromCrossRef(t)
					.orElse(t)
			);
	}
	@Override
	public Optional<String> getPublisher() { return this.asString("publisher"); }
	@Override
	public Provenance getProvenance() {
		return Provenance.canonical(Provenance.CROSSREF, this.asString("type"));
	}

	@Override
	public Optional<String> getTitle() {
		return this.streamPath("title")
			.findFirst()
			.map(n -> PlainText.of(n.asString()));
	}
	@Override
	public Optional<String> getFirstAuthorName() {
		return this.getFirstAuthor()
			.map(Author::getLastName);
	}
	@Override
	public Optional<String> getJournal() {
		return this.streamNode("container-title")
			.map(ExtensibleJson::asString)
			.findFirst();
	}
	@Override
	public Optional<String> getVolume() { return this.asString("volume"); }
	@Override
	public Optional<String> getIssue() { return this.asString("issue"); }
	@Override
	public Optional<Long> getYear() {
		return this.streamPath("published-print", "date-parts")
			.findFirst()
			.stream() // weird nested array
			.findFirst()
			.flatMap(StreamExceptions.ignoreFunction(ExtensibleJson::asLong))
			.or(
				() -> this.getDate()
					.map(d -> (long) d.getYear())
			);
	}
	@Override
	public Optional<String> getPage() { return this.asString("page"); }

	@Override
	public Optional<LocalDate> getDate() {
		return this.streamPath("created", "date-time")
			.map(ExtensibleJson::asString)
			.map(s -> LocalDate.parse(s, DateTimeFormatter.ISO_DATE_TIME))
			.findFirst();
	}

	public Optional<Double> getScore() { return this.asDouble("score"); }
	@Override
	public Stream<String> getLicenses() {
		return this.streamPath("license", "URL")
			.map(ExtensibleJson::asString);
	}

	@Override
	public List<Author> getAuthors() {
		return this.streamPath(CrossRefContributor.class, "author")
			.collect(Collectors.toList());
	}
	public Stream<CrossRefReference> getReferences() {
		return this.streamPath(CrossRefReference.class, "reference");
	}

	@Override
	public List<CitationLink> getCitations() {
		List<CitationLink> tmp = new ArrayList<>();
		Integer i = 1;
		for (CrossRefReference r : (Iterable<CrossRefReference>) this
			.getReferences()::iterator) {
			tmp.add(
				ImmutableCitationLink.builder()
					.source(Builder.citationReference(this))
					.target(Builder.citationReference(r))
					.index(i)
					.build()
			);

			i += 1;
		}
		return tmp;
	}

	@Override
	public Optional<Long> getCitedByCount() {
		return this.asLong("is-referenced-by-count");
	}
	@Override
	public Optional<Long> getReferencesCount() {
		return this.asLong("references-count");
	}
	// Crossref returns this as JATS XML, so it is reduced to text at the boundary
	@Override
	public Optional<String> getAbstract() {
		return this.asString("abstract")
			.map(PlainText::of);
	}

	public Optional<URI> getTextMiningUri() {
		// text-mining, similarity-checking or unspecified
		return this.streamPath("links")
			.filter(
				n -> n.asString("intended-application")
					.filter(s -> s.equals("text-mining"))
					.isPresent()
			)
			.flatMap(
				n -> n.asString("URL")
					.stream()
			)
			.map(URI::create)
			.findFirst();
	}

	@Override
	public List<RecordReference> getOtherIdentifiers() {
		return Collections.emptyList();
	}

	@Override
	public Optional<URI> getPdfUri() { return Optional.empty(); }

	/*
	 * @JsonProperty("publisher") public Optional<String> publisher = Optional.empty(); // Yes-Name of work's publisher
	 * 
	 * @JsonProperty("title") public List<String> title = Collections.emptyList(); // Yes-Work titles, including translated
	 * titles
	 * 
	 * @JsonProperty("original-title") public List<String> originalTitle = Collections.emptyList(); // No-Work titles in the
	 * work's original publication language
	 * 
	 * @JsonProperty("short-title") public List<String> shortTitle = Collections.emptyList(); // No-Short or abbreviated
	 * work titles
	 * 
	 * @JsonProperty("abstract") public Optional<String> journalAbstract = Optional.empty(); // No-Abstract as a JSON string
	 * or a JATS XML snippet encoded into a JSON string
	 * 
	 * @JsonProperty("reference-count") public Optional<Integer> referenceCount = Optional.empty(); // Yes-Deprecated Same
	 * as references-count
	 * 
	 * @JsonProperty("references-count") public Optional<Integer> referencesCount = Optional.empty(); // Yes-Count of
	 * outbound references deposited with Crossref
	 * 
	 * @JsonProperty("is-referenced-by-count") public Optional<Integer> isReferencedByCount = Optional.empty(); // Yes-Count
	 * of inbound references deposited with Crossref
	 * 
	 * @JsonProperty("source") public Optional<String> source = Optional.empty(); // Yes-Currently always Crossref
	 * 
	 * @JsonProperty("prefix") public Optional<String> prefix = Optional.empty(); // Yes-DOI prefix identifier of the form
	 * http://id.crossref.org/prefix/DOIPREFIX
	 * 
	 * @JsonProperty("DOI") public Optional<String> DOI = Optional.empty(); // Yes-DOI of the work
	 * 
	 * @JsonProperty("URL") public Optional<URL> URL = Optional.empty(); // Yes-URL form of the work's DOI
	 * 
	 * @JsonProperty("member") public Optional<String> member = Optional.empty(); // Yes-Member identifier of the form
	 * http://id.crossref.org/member/MEMBERID
	 * 
	 * @JsonProperty("type") public Optional<String> type = Optional.empty(); // Yes-Enumeration, one of the type ids from
	 * https://api.crossref.org/v1/types
	 * 
	 * @JsonProperty("created") public Optional<Date> created = Optional.empty(); // Yes-Date on which the DOI was first
	 * registered
	 * 
	 * @JsonProperty("deposited") public Optional<Date> deposited = Optional.empty(); // Yes-Date on which the work metadata
	 * was most recently updated
	 * 
	 * @JsonProperty("indexed") public Optional<Date> indexed = Optional.empty(); // Yes-Date on which the work metadata was
	 * most recently indexed. Re-indexing does not imply a metadata change, see deposited for the most recent metadata
	 * change date
	 * 
	 * @JsonProperty("issued") public Optional<PartialDate> issued = Optional.empty(); // Yes-Earliest of published-print
	 * and published-online
	 * 
	 * @JsonProperty("posted") public Optional<PartialDate> posted = Optional.empty(); // No-Date on which posted content
	 * was made available online
	 * 
	 * @JsonProperty("accepted") public Optional<PartialDate> accepted = Optional.empty(); // No-Date on which a work was
	 * accepted, after being submitted, during a submission process
	 * 
	 * @JsonProperty("subtitle") public List<String> subtitle = Collections.emptyList(); // No-Work subtitles, including
	 * original language and translated
	 * 
	 * @JsonProperty("container-title") public List<String> containerTitle = Collections.emptyList(); // No-Full titles of
	 * the containing work (usually a book or journal)
	 * 
	 * @JsonProperty("short-container-title") public List<String> shortContainerTitle = Collections.emptyList(); //
	 * No-Abbreviated titles of the containing work
	 * 
	 * @JsonProperty("group-title") public Optional<String> groupTitle = Optional.empty(); // No-Group title for posted
	 * content
	 * 
	 * @JsonProperty("issue") public Optional<String> issue = Optional.empty(); // No-Issue Integer of an article's journal
	 * 
	 * @JsonProperty("volume") public Optional<String> volume = Optional.empty(); // No-Volume Integer of an article's
	 * journal
	 * 
	 * @JsonProperty("page") public Optional<String> page = Optional.empty(); // No-Pages numbers of an article within its
	 * journal
	 * 
	 * @JsonProperty("article-number") public Optional<String> articleNumber = Optional.empty(); // No-
	 * 
	 * @JsonProperty("published-print") public Optional<PartialDate> publishedPrint = Optional.empty(); // No-Date on which
	 * the work was published in print
	 * 
	 * @JsonProperty("published-online") public Optional<PartialDate> publishedOnline = Optional.empty(); // No-Date on
	 * which the work was published online
	 * 
	 * @JsonProperty("subject") public List<String> subject = Collections.emptyList(); // No-Subject category names, a
	 * controlled vocabulary from Sci-Val. Available for most journal articles
	 * 
	 * @JsonProperty("ISSN") public List<String> ISSN = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("issn-type") public List<ISSNWithType> issnType = Collections.emptyList(); // No-List of ISSNs with
	 * ISSN type information
	 * 
	 * @JsonProperty("ISBN") public List<String> ISBN = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("archive") public List<String> archive = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("license") public List<License> license = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("funder") public List<Funder> funder = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("assertion") public List<Assertion> assertion = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("author") public List<Contributor> author = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("editor") public List<Contributor> editor = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("chair") public List<Contributor> chair = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("translator") public List<Contributor> translator = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("update-to") public List<Update> updateTo = Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("update-policy") public Optional<URL> updatePolicy = Optional.empty(); // No-Link to an update policy
	 * covering Crossmark updates for this work
	 * 
	 * @JsonProperty("link") public List<ResourceLink> link = Collections.emptyList(); // No-URLs to full-text locations
	 * 
	 * @JsonProperty("clinical-trial-Integer") public List<ClinicalTrialNumber> clinicalTrialNumber =
	 * Collections.emptyList(); // No-
	 * 
	 * @JsonProperty("alternative-id") public List<String> alternativeId = Collections.emptyList(); // No-Other identifiers
	 * for the work provided by the depositing member
	 * 
	 * @JsonProperty("reference") public List<Reference> reference = Collections.emptyList(); // No-List of references made
	 * by the work
	 * 
	 * @JsonProperty("content-domain") public Optional<ContentDomain> contentDomain = Optional.empty(); // No-Information on
	 * domains that support Crossmark for this work
	 * 
	 * @JsonProperty("relation") public Map<String,List<Relation>> relation; // No-Relations to other works
	 * 
	 * @JsonProperty("review") public Optional<Review> review = Optional.empty(); // No-Peer review metadata
	 * 
	 * @JsonProperty("language") public Optional<String> language = Optional.empty(); //NOT IN SPEC
	 * 
	 * @JsonProperty("score") public Optional<Float> score = Optional.empty(); //NOT IN SPEC
	 * 
	 * @JsonProperty("edition-number") public Optional<Integer> editionNumber = Optional.empty(); //NOT IN SPEC
	 * 
	 * @JsonProperty("event") public Optional<Event> event = Optional.empty(); //NOT IN SPEC
	 * 
	 * @JsonProperty("journal-issue") public Optional<JournalIssue> journalIssue = Optional.empty(); //NOT IN SPEC
	 * 
	 * @JsonProperty("isbn-type") public List<ISSNWithType> isbnType = Collections.emptyList(); //NOT IN SPEC
	 * 
	 * @JsonProperty("publisher-location") public Optional<String> publisherLocation = Optional.empty(); //NOT IN SPEC
	 */
}