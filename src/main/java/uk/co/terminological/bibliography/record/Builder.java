package uk.co.terminological.bibliography.record;

import java.util.List;
import java.util.Optional;

public class Builder {

	public static ImmutableCitationReference citationReference(
			CitationReference link
	) {
		if (link == null) return null;
		if (link instanceof ImmutableCitationReference)
			return (ImmutableCitationReference) link;
		return ImmutableCitationReference.copyOf(link);
	}

	public static ImmutableCitationLink citationLink(CitationLink link) {
		if (link == null) return null;
		if (link instanceof ImmutableCitationLink)
			return (ImmutableCitationLink) link;
		return ImmutableCitationLink.copyOf(link);
	}

	public static ImmutablePrintReference printReference(PrintReference link) {
		if (link == null) return null;
		if (link instanceof ImmutablePrintReference)
			return (ImmutablePrintReference) link;
		return ImmutablePrintReference.copyOf(link);
	}

	public static ImmutableAuthor author(Author link) {
		if (link == null) return null;
		if (link instanceof ImmutableAuthor) return (ImmutableAuthor) link;
		return ImmutableAuthor.copyOf(link);
	}

	public static ImmutableRecord record(Record link) {
		if (link == null) return null;
		if (link instanceof ImmutableRecord) return (ImmutableRecord) link;
		return ImmutableRecord.copyOf(link);
	}

	public static ImmutableRecordWithCitations recordWithCitations(Record link) {
		if (link == null) return null;
		if (link instanceof RecordWithCitations)
			return ImmutableRecordWithCitations.copyOf((RecordWithCitations) link);
		return ImmutableRecordWithCitations.builder()
			.from(link)
			.addAllCitations(List.of())
			.build();
	}

	public static ImmutableRecordReference recordReference(
			RecordReference link
	) {
		if (link == null) return null;
		if (link instanceof ImmutableRecordReference)
			return (ImmutableRecordReference) link;
		return ImmutableRecordReference.copyOf(link);
	}

//	public static ImmutableCitationLink citationLink(CitationReference source, CitationReference target, Optional<Integer> indexOf) {
//		
//		return new ImmutableCitationLink() {
//			ImmutableCitationReference source = Builder.citationReference(source);
//			ImmutableCitationReference target = Builder.citationReference(target);
//			Integer index = indexOf.orElse(null); 
//			@Override
//			public ImmutableCitationReference getSource() {
//				return source;
//			}
//			@Override
//			public ImmutableCitationReference getTarget() {
//				return target;
//			}
//			@Override
//			public Optional<Integer> getIndex() {
//				return Optional.of(index);
//			};
//		};
//	}

//	public static CitationReference citationReference(
//			RecordReference identifier,
//			String title,
//			PrintReference bibliographicId
//			) {
//		return citationReference(
//				Optional.ofNullable(identifier),
//				Optional.ofNullable(title),
//				Optional.ofNullable(bibliographicId)
//		);
//	}

//	public static Optional<ImmutableRecordReference> recordReference(RecordReference ref) {
//		if (ref instanceof ImmutableRecordReference) return Optional.of((ImmutableRecordReference) ref);
//		if (!ref.getIdentifier().isPresent()) return Optional.empty();			
//		ImmutableRecordReference out = new ImmutableRecordReference();
//		out.id = ref.getIdentifier().get();
//		out.idType = ref.getIdentifierType();
//		return Optional.of(out);
//	}

	public static ImmutableRecordReference recordReference(
			IdType idType, String id
	) {
		var out = ImmutableRecordReference.builder()
			.identifierType(idType)
			.identifier(id)
			.build();
		return out;
	}

	public static ImmutableRecordReferenceMapping recordReferenceMapping(
			RecordReference src, RecordReference targ
	) {
		return ImmutableRecordReferenceMapping.builder()
			.source(src)
			.target(targ)
			.build();
	}

	public static ImmutableCitationReference citationReference(
			RecordReference record
	) {
		String title = null;
		if (record instanceof Record) {
			title = ((Record) record).getTitle()
				.orElse(null);
		}
		PrintReference print = null;
		if (record instanceof PrintReference) { print = (PrintReference) record; }
		return citationReference(
			record.asId(),
			Optional.of(title),
			Optional.of(print)
		);
	}

	public static ImmutableCitationLink citationLink(
			RecordReference source, RecordReference target, Integer nullableIndex
	) {
		return ImmutableCitationLink.builder()
			.source(citationReference(source))
			.target(citationReference(target))
			.index(Optional.ofNullable(nullableIndex))
			.build();
	}

	public static ImmutableCitationLink citationLink(
			CitationReference source, CitationReference target,
			Integer nullableIndex
	) {
		return ImmutableCitationLink.builder()
			.source(source)
			.target(target)
			.index(Optional.ofNullable(nullableIndex))
			.build();
	}

	public static ImmutableCitationReference citationReference(
			RecordReference identifier, Optional<String> title,
			Optional<PrintReference> bib
	) {
		return ImmutableCitationReference.builder()
			.identifier(identifier)
			.title(title)
			.bibliographicId(bib)
			.build();
	}

//	public static Optional<ImmutableRecordReferenceMapping> recordIdMapping(RecordReference source, RecordReference target) {
//		if (!Builder.recordReference(source).isPresent() || !Builder.recordReference(source).isPresent()) return Optional.empty();
//		return Optional.of(new ImmutableRecordReferenceMapping(
//				Builder.recordReference(source).get(), 
//				Builder.recordReference(target).get()));
//	}

}
