package uk.co.terminological.bibliography.record;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.immutables.value.Value;

import uk.co.terminological.bibliography.CiteProcProvider;
import uk.co.terminological.bibliography.CiteProcProvider.Format;

/**
 * What one source says about one work: an identity, plus the bibliographic claims made about it.
 *
 * A {@code Record} is a <strong>statement by a source</strong>, not a library entry and not the truth. Two sources
 * describing the same work give two records that may disagree, and resolving that is {@link MergedRecord}'s job, not
 * this interface's — which is why {@link #getProvenance()} is required rather than optional: a claim with no
 * attribution cannot be weighed against a competing one.
 *
 * The scope is deliberately narrow. Notes, tags, collections, attachments beyond a single pdf link, and full text all
 * belong to a repository rather than to a statement about a work, and stay outside the model; the Zotero bridge has
 * its own surface for them. What is in scope is whatever is needed to identify a work, say what kind of thing it is,
 * and carry the fields that kind actually has.
 *
 * The API classes implement this directly over their own payloads rather than copying into it, so a field this
 * interface does not name is still reachable on the implementation.
 */
// [IMPLEMENTS](/design/features/feat-006-classify-records-by-type-with-provenance.md)
@Value.Immutable
public interface Record extends RecordReference {

	/**
	 * Other identifiers this source gave for the same work. The main route by which records from different APIs are
	 * recognised as describing one work, alongside {@link RecordReferenceMapping}.
	 */
	List<RecordReference> getOtherIdentifiers();

	/**
	 * The kind of work, in the CSL item-type vocabulary (see {@link PublicationTypes}). A string rather than an enum so
	 * unknown values survive; sources pass their own value through when no mapping exists. Defaults to empty so that the
	 * existing implementations keep compiling.
	 */
	@Value.Default
	default Optional<String> getPublicationType() { return Optional.empty(); }

	/** The publisher or issuing body: a journal's publisher, a book's publisher, a report's institution. */
	@Value.Default
	default Optional<String> getPublisher() { return Optional.empty(); }

	/** Where this record's claims came from. */
	Provenance getProvenance();

	/**
	 * The title of the container this work appears in: journal, book, proceedings. Defaults to {@link #getJournal()} for
	 * records that only know that one field.
	 */
	default Optional<String> getContainerTitle() { return this.getJournal(); }

	default Optional<? extends Author> getFirstAuthor() {
		if (this.getAuthors()
			.isEmpty()) return Optional.empty();
		return Optional.ofNullable(
			this.getAuthors()
				.get(0)
		);
	}

	List<Author> getAuthors();
	Stream<String> getLicenses();

	Optional<String> getAbstract();
	Optional<String> getTitle();
	Optional<String> getJournal();
	Optional<LocalDate> getDate();

	Optional<URI> getPdfUri();

	default Optional<String> getFirstAuthorLastName() {
		return this.getFirstAuthor()
			.map(Author::getLastName);
	}

	default Optional<String> getFirstAuthorFirstName() {
		return this.getFirstAuthor()
			.flatMap(Author::getFirstName);
	}

	default Optional<String> render(String style) {
		if (style == null) return Optional.of(Record.print(this));
		try {
			return Optional.of(
				CiteProcProvider.convert(style, Format.text, this)
					.getEntries()[0]
			); // .replaceFirst("^\\[[0-9]+\\]", ""));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	default Set<ImmutableRecordReference> getIdentifiers() {
		Set<ImmutableRecordReference> tmp = new LinkedHashSet<>();
		if (this.getIdentifier()
			.isPresent()) {
			tmp.add(
				Builder.recordReference(
					this.getIdentifierType(),
					this.getIdentifier()
						.get()
				)
			);
		}
		this.getOtherIdentifiers()
			.forEach(i -> {
				if (i.getIdentifier()
					.isPresent()) { i.asId()
						.ifComplete(tmp::add); }
			});
		return (tmp);
	}

	default Optional<String> getIdentifier(IdType type) {
		if (this.getIdentifierType()
			.equals(type)) return this.getIdentifier();
		return this.getOtherIdentifiers()
			.stream()
			.filter(
				s -> s.getIdentifierType()
					.equals(type)
			)
			.flatMap(
				s -> s.getIdentifier()
					.stream()
			)
			.findFirst();
	}

	default Stream<ImmutableRecordReferenceMapping> getMappings() {
		List<ImmutableRecordReference> tmp2 = new ArrayList<>(
				this.getIdentifiers()
		);
		List<ImmutableRecordReferenceMapping> tmp3 = new ArrayList<>();
		for (var i = 0; i < tmp2.size(); i++) {
			for (var j = i + 1; j < tmp2.size(); j++) {
				tmp3.add(
					ImmutableRecordReferenceMapping.builder()
						.source(tmp2.get(i))
						.target(tmp2.get(j))
						.build()
				);
			}
		}
		return tmp3.stream();
	}

	static String print(Record r) {
		return r.getFirstAuthorLastName()
			.orElse("Unknown") + ", "
				+ r.getFirstAuthorFirstName()
					.orElse("Author")
				+ " - " + r.getTitle()
					.orElse("Unknown title")
				+ "; (" + r.getDate()
					.map(d -> Integer.toString(d.getYear()))
					.orElse("Unknown year")
				+ ") " + r.getJournal()
					.orElse("Unknown journal")
				+ ". " + r.getIdentifiers()
					.stream()
					.map(RecordReference::print)
					.collect(Collectors.joining(" "));
	}

}
