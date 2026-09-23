package uk.co.terminological.bibliography.record;

import java.io.Serializable;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// [IMPLEMENTS](/design/features/feat-006-classify-records-by-type-with-provenance.md)
public class ImmutableRecord implements RecordWithCitations,Serializable {

	private String identifier;
	private IdType identifierType;
	private Set<ImmutableRecordReference> recordIdentifiers;
	private List<ImmutableAuthor> authors;
	private Collection<String> licences;
	private String abst;
	private String title;
	private String journal;
	private String publicationType;
	private String publisher;
	private Provenance provenance;
	private LocalDate date;
	private URI uri;
	private Long citedBy;
	private Long references;
	private List<ImmutableCitationLink> citations;

	public ImmutableRecord(Record record) {
		this.identifier = record.getIdentifier().orElse(null);
		this.identifierType = record.getIdentifierType();
		this.recordIdentifiers = record.getIdentifiers();
		this.authors = record.getAuthors().stream().map(Builder::author).collect(Collectors.toList());
		this.licences = record.getLicenses().collect(Collectors.toList());
		this.abst = record.getAbstract().orElse(null);
		this.title = record.getTitle().orElse(null);
		this.journal = record.getJournal().orElse(null);
		this.publicationType = record.getPublicationType().orElse(null);
		this.publisher = record.getPublisher().orElse(null);
		this.provenance = record.getProvenance().orElse(null);
		this.date = record.getDate().orElse(null);
		this.uri = record.getPdfUri().orElse(null);
		if(record instanceof RecordWithCitations) {
			this.citedBy = ((RecordWithCitations) record).getCitedByCount().orElse(null);
			this.references =  ((RecordWithCitations) record).getReferencesCount().orElse(null);
			this.citations = ((RecordWithCitations) record).getCitations().map(Builder::citationLink).collect(Collectors.toList());
		} else {
			this.citations = Collections.emptyList();
		}
	}

	@Override
	public Optional<String> getIdentifier() {
		return Optional.ofNullable(this.identifier).map(s->s.replaceAll("^PMC", ""));
	}

	@Override
	public IdType getIdentifierType() {
		return identifierType;
	}

	@Override
	public List<ImmutableRecordReference> getOtherIdentifiers() {
		return new ArrayList<>(this.recordIdentifiers);
	}

	@Override
	public List<ImmutableAuthor> getAuthors() {
		return this.authors;
	}

	@Override
	public Stream<String> getLicenses() {
		return licences.stream();
	}

	@Override
	public Optional<String> getAbstract() {
		return Optional.ofNullable(abst);
	}

	@Override
	public Optional<String> getTitle() {
		return Optional.ofNullable(title);
	}

	@Override
	public Optional<String> getJournal() {
		return Optional.ofNullable(journal);
	}

	@Override
	public Optional<String> getPublicationType() {
		return Optional.ofNullable(publicationType);
	}

	@Override
	public Optional<String> getPublisher() {
		return Optional.ofNullable(publisher);
	}

	@Override
	public Optional<Provenance> getProvenance() {
		return Optional.ofNullable(provenance);
	}

	@Override
	public Optional<LocalDate> getDate() {
		return Optional.ofNullable(date);
	}

	@Override
	public Optional<URI> getPdfUri() {
		return Optional.ofNullable(uri);
	}

	@Override
	public Stream<? extends CitationLink> getCitations() {
		return citations.stream();
		
	}

	@Override
	public Optional<Long> getCitedByCount() {
		return Optional.ofNullable(citedBy);
	}

	@Override
	public Optional<Long> getReferencesCount() {
		return Optional.ofNullable(references);
	}

	

}
