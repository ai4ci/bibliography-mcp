package uk.co.terminological.bibliography.repository;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * The least a {@link Record} can be: an identity, a title, and where the claim came from.
 *
 * Shared by the tests that need something in a repository without needing it to have come from an API.
 */
public class RecordFixture implements Record {

	private final String id;
	private final String title;
	private final String source;

	public RecordFixture(String id, String title) { this(id, title, "crossref"); }

	public RecordFixture(String id, String title, String source) {
		this.id = id;
		this.title = title;
		this.source = source;
	}

	@Override
	public Optional<String> getIdentifier() { return Optional.of(this.id); }
	@Override
	public IdType getIdentifierType() { return IdType.DOI; }
	@Override
	public List<RecordReference> getOtherIdentifiers() { return List.of(); }
	@Override
	public List<Author> getAuthors() { return List.of(); }
	@Override
	public Stream<String> getLicenses() { return Stream.empty(); }
	@Override
	public Optional<String> getAbstract() { return Optional.empty(); }
	@Override
	public Optional<String> getTitle() { return Optional.of(this.title); }
	@Override
	public Optional<String> getJournal() { return Optional.empty(); }
	@Override
	public Optional<LocalDate> getDate() { return Optional.empty(); }
	@Override
	public Optional<URI> getPdfUri() { return Optional.empty(); }
	@Override
	public Provenance getProvenance() {
		return Provenance.of(this.source, "journal-article");
	}

}
