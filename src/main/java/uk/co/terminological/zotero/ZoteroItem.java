package uk.co.terminological.zotero;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.PublicationTypes;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * A Zotero item presented as a unified record.
 *
 * The mapping is deliberately lossy at the edges (a `Record` is a statement about
 * a work, not a library entry): notes, tags, collections and child attachments
 * live in the library, not here, and the item key travels in the provenance as
 * instance-scoped linkage rather than as an identifier of the work.
 *
 * [IMPLEMENTS](/design/features/feat-005-zotero-curated-repository-bridge.md)
 */
public class ZoteroItem implements Record {

	private static final String[] CONTAINER_FIELDS = {
			"publicationTitle", "bookTitle", "proceedingsTitle", "websiteTitle",
			"blogTitle", "encyclopediaTitle", "dictionaryTitle" };

	private final JsonNode data;
	private final String serverId;

	public ZoteroItem(JsonNode data, String serverId) {
		this.data = data;
		this.serverId = serverId;
	}

	/** the Zotero item key, needed to write back to this item */
	public String getKey() {
		return text("key").orElse(null);
	}

	/** the raw item data, for the write path and for reporting disagreements */
	public JsonNode getData() {
		return data;
	}

	// ---- identity ------------------------------------------------------------

	@Override
	public Optional<String> getIdentifier() {
		return getIdentifier(IdType.DOI)
				.or(() -> getIdentifier(IdType.ISBN));
	}

	@Override
	public IdType getIdentifierType() {
		if (getIdentifier(IdType.DOI).isPresent()) return IdType.DOI;
		if (getIdentifier(IdType.ISBN).isPresent()) return IdType.ISBN;
		return IdType.UNK;
	}

	@Override
	public Optional<String> getIdentifier(IdType type) {
		// the identifier is exposed as Zotero holds it (case and ISBN hyphens are
		// part of how it is written); normalisation happens only in the match keys
		if (type == IdType.DOI) return text("DOI").map(ZoteroItem::stripResolverPrefix);
		if (type == IdType.ISBN) return text("ISBN");
		return Optional.empty();
	}

	/** a DOI stored as a URL is still a DOI */
	private static String stripResolverPrefix(String raw) {
		String out = raw.trim();
		out = out.replaceFirst("^https?://(dx\\.)?doi\\.org/", "");
		out = out.replaceFirst("^(?i)doi:", "");
		return out.trim().isEmpty() ? null : out.trim();
	}

	@Override
	public List<? extends RecordReference> getOtherIdentifiers() {
		List<ImmutableRecordReference> out = new ArrayList<>();
		if (getIdentifierType() != IdType.DOI) getIdentifier(IdType.DOI).ifPresent(v -> out.add(Builder.recordReference(IdType.DOI, v)));
		if (getIdentifierType() != IdType.ISBN) getIdentifier(IdType.ISBN).ifPresent(v -> out.add(Builder.recordReference(IdType.ISBN, v)));
		return out;
	}

	// ---- description ---------------------------------------------------------

	@Override
	public Optional<String> getTitle() { return text("title"); }

	@Override
	public Optional<String> getAbstract() { return text("abstractNote"); }

	/** the container this work appears in: journal, book, proceedings, website */
	@Override
	public Optional<String> getJournal() {
		for (String field : CONTAINER_FIELDS) {
			Optional<String> value = text(field);
			if (value.isPresent()) return value;
		}
		return Optional.empty();
	}

	/** publisher, or the issuing institution for a report (as Zotero's own CSL export does) */
	@Override
	public Optional<String> getPublisher() {
		return text("publisher").map(Optional::of).orElseGet(() -> text("institution"));
	}

	@Override
	public Optional<String> getPublicationType() {
		return text("itemType").map(t -> PublicationTypes.fromZotero(t).orElse(t));
	}

	@Override
	public List<? extends Author> getAuthors() {
		List<Author> all = new ArrayList<>();
		List<Author> authors = new ArrayList<>();
		JsonNode creators = data.get("creators");
		if (creators != null && creators.isArray()) {
			for (JsonNode creator : creators) {
				Creator parsed = new Creator(creator);
				all.add(parsed);
				if (parsed.isAuthor()) authors.add(parsed);
			}
		}
		// a book may have only editors; prefer authors when the item has any
		return authors.isEmpty() ? all : authors;
	}

	/**
	 * Zotero dates are free text ("2013", "2013-05", "2026-09-16", "Spring 2022").
	 * A `LocalDate` cannot express a year or a month, so precision is filled
	 * downwards (year → Jan 1, year-month → the 1st) rather than dropping the date
	 * entirely and losing year matching. Unrecognised text yields no date.
	 */
	@Override
	public Optional<LocalDate> getDate() {
		return text("date").flatMap(ZoteroItem::parseDate);
	}

	static Optional<LocalDate> parseDate(String raw) {
		String t = raw.trim();
		if (t.isEmpty()) return Optional.empty();
		try {
			return Optional.of(LocalDate.parse(t));
		} catch (DateTimeParseException ignored) { }
		// "2026-09-16 00:00:00"
		if (t.length() >= 10) {
			try {
				return Optional.of(LocalDate.parse(t.substring(0, 10)));
			} catch (DateTimeParseException ignored) { }
		}
		if (t.matches("\\d{4}-\\d{2}")) {
			String[] parts = t.split("-");
			return Optional.of(LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1));
		}
		Matcher m = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)").matcher(t);
		if (m.find()) {
			return Optional.of(LocalDate.of(Integer.parseInt(m.group(1)), 1, 1));
		}
		return Optional.empty();
	}

	@Override
	public Stream<String> getLicenses() {
		return text("rights").map(Stream::of).orElseGet(Stream::empty);
	}

	/** attachments are child items; resolving them is deferred (plan-004) */
	@Override
	public Optional<URI> getPdfUri() {
		return Optional.empty();
	}

	@Override
	public Optional<Provenance> getProvenance() {
		// the key is instance-scoped linkage, qualified by the server id so it is
		// never mistaken for an identifier of the work
		String url = getKey() == null ? null : "zotero:" + serverId + "/items/" + getKey();
		return Optional.of(Provenance.of(Provenance.ZOTERO, text("itemType").orElse(null), null, url));
	}

	private Optional<String> text(String field) {
		JsonNode node = data.get(field);
		if (node == null || node.isNull()) return Optional.empty();
		String value = node.asText();
		return value == null || value.trim().isEmpty() ? Optional.empty() : Optional.of(value);
	}

	/** a Zotero creator: person names, or a single `name` for an institution */
	static final class Creator implements Author {
		private final String firstName;
		private final String lastName;
		private final String creatorType;

		Creator(JsonNode node) {
			this.creatorType = node.path("creatorType").asText("");
			String institutional = node.path("name").asText(null);
			if (institutional != null && !institutional.trim().isEmpty()) {
				this.firstName = null;
				this.lastName = institutional.trim();
			} else {
				this.firstName = node.path("firstName").asText(null);
				this.lastName = node.path("lastName").asText("");
			}
		}

		boolean isAuthor() { return "author".equals(creatorType); }

		public Optional<String> getORCID() { return Optional.empty(); }
		public Optional<String> getFirstName() { return Optional.ofNullable(firstName); }
		public String getLastName() { return lastName == null ? "" : lastName; }
		public Optional<String> getInitials() { return Optional.empty(); }
		public Stream<String> getAffiliations() { return Stream.empty(); }
	}
}
