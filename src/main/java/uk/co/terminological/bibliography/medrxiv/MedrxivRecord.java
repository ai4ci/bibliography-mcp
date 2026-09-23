package uk.co.terminological.bibliography.medrxiv;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.PlainText;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.PublicationTypes;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * One version of a medRxiv or bioRxiv preprint, as `api.biorxiv.org` describes it.
 *
 * A preprint is a `posted-content` record whose container is the server it was
 * posted to, and it has no publisher: the server is a repository, not a publisher,
 * and inventing one would be worse than leaving the field empty.
 *
 * [IMPLEMENTS](/design/features/feat-009-preprint-clients.md)
 */
public class MedrxivRecord implements Record {

	private final JsonNode version;
	private final String server;

	public MedrxivRecord(JsonNode version, String server) {
		this.version = version;
		this.server = server;
	}

	public JsonNode getRaw() { return version; }

	/** the server's own name for this record's kind, e.g. PUBLISHAHEADOFPRINT */
	public Optional<String> getServerType() { return text("type"); }

	public Optional<String> getJatsXmlUrl() { return text("jatsxml"); }

	public Optional<String> getVersion() { return text("version"); }

	@Override
	public Optional<String> getIdentifier() { return text("doi"); }

	@Override
	public IdType getIdentifierType() { return IdType.DOI; }

	@Override
	public List<? extends RecordReference> getOtherIdentifiers() {
		// the `published` field is the DOI of the *published article*, a different work
		// that Zotero should hold separately, so it is deliberately not an identifier here
		return List.of();
	}

	@Override
	public Optional<String> getTitle() { return text("title").map(PlainText::of); }

	@Override
	public Optional<String> getAbstract() { return text("abstract").map(PlainText::of); }

	/** the container of a preprint is the server it was posted to */
	@Override
	public Optional<String> getJournal() { return Optional.of(server); }

	@Override
	public Optional<String> getPublisher() { return Optional.empty(); }

	@Override
	public Optional<String> getPublicationType() { return Optional.of(PublicationTypes.POSTED_CONTENT); }

	/**
	 * The API gives authors as "Surname, Initials; Surname, Initials;", and an
	 * institutional author as a bare name ("H1 Collaboration").
	 */
	@Override
	public List<? extends Author> getAuthors() {
		List<Author> out = new ArrayList<>();
		for (String entry : text("authors").orElse("").split(";")) {
			String name = entry.trim();
			if (name.isEmpty()) continue;
			int comma = name.indexOf(',');
			out.add(new MedrxivAuthor(
					comma < 0 ? name : name.substring(0, comma).trim(),
					comma < 0 ? null : name.substring(comma + 1).trim()));
		}
		return out;
	}

	@Override
	public Optional<LocalDate> getDate() {
		return text("date").flatMap(raw -> {
			try {
				return Optional.of(LocalDate.parse(raw.trim()));
			} catch (DateTimeParseException e) {
				return Optional.empty();
			}
		});
	}

	@Override
	public Stream<String> getLicenses() {
		return text("license").map(Stream::of).orElseGet(Stream::empty);
	}

	/** the full text is XML rather than a PDF, so there is no PDF location here */
	@Override
	public Optional<URI> getPdfUri() { return Optional.empty(); }

	@Override
	public Optional<Provenance> getProvenance() {
		String source = "biorxiv".equals(server) ? Provenance.BIORXIV : Provenance.MEDRXIV;
		String landingPage = getIdentifier()
				.map(doi -> "https://www." + server.toLowerCase() + ".org/content/" + doi)
				.orElse(null);
		return Optional.of(Provenance.of(source, text("type").orElse(null), null, landingPage));
	}

	private Optional<String> text(String field) {
		JsonNode node = version.get(field);
		if (node == null || node.isNull()) return Optional.empty();
		String value = node.asText();
		return value == null || value.trim().isEmpty() ? Optional.empty() : Optional.of(value.trim());
	}

	/** a person, or an institution whose whole name is its last name */
	static final class MedrxivAuthor implements Author {
		private final String lastName;
		private final String initials;

		MedrxivAuthor(String lastName, String initials) {
			this.lastName = lastName;
			this.initials = initials;
		}

		public Optional<String> getORCID() { return Optional.empty(); }
		public Optional<String> getFirstName() { return Optional.empty(); }
		public String getLastName() { return lastName; }
		public Optional<String> getInitials() { return Optional.ofNullable(initials); }
		public Stream<String> getAffiliations() { return Stream.empty(); }
	}

	/** the requested reference for this record, for keying fetch results */
	ImmutableRecordReference asReference() {
		return Builder.recordReference(IdType.DOI, getIdentifier().orElse(null));
	}
}
