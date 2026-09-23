package uk.co.terminological.bibliography.record;

import java.io.Serializable;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// [IMPLEMENTS](/design/features/feat-006-classify-records-by-type-with-provenance.md)
// [IMPLEMENTS](/design/features/feat-007-predictable-record-merging.md)
public class MergedRecord implements RecordWithCitations, Serializable {

	private static final long serialVersionUID = 1L;

	List<ImmutableRecord> records = new ArrayList<>();

	private final MergePolicy policy = MergePolicy.standard();

	public static MergedRecord from(Record r) {
		if (r instanceof MergedRecord) return (MergedRecord) r;
		MergedRecord out = new MergedRecord();
		return out.merge(r);
	}

	public MergedRecord merge(Record r) {
		if (r instanceof MergedRecord) {
			// absorb each contribution rather than flattening the merged view into a
			// single record, which would discard every contribution but the first
			// (this is the path BiblioFacade.fetch takes when it merges per-API results)
			((MergedRecord) r).records.forEach(this::merge);
			return this;
		}
		ImmutableRecord candidate = Builder.record(r);
		// an exact re-contribution (same source, identifier and content) adds
		// nothing but memory, so repeated fetches do not inflate the merge
		if (records.stream().noneMatch(existing -> sameContribution(existing, candidate))) {
			records.add(candidate);
		}
		return this;
	}

	private static boolean sameContribution(Record a, Record b) {
		return contributionKey(a).equals(contributionKey(b))
				&& a.getIdentifier().equals(b.getIdentifier())
				&& a.getTitle().equals(b.getTitle())
				&& a.getAbstract().equals(b.getAbstract());
	}

	/** two records without provenance are not the same source just because both are unnamed */
	private static String contributionKey(Record r) {
		String source = MergePolicy.sourceOf(r);
		return source.isEmpty() ? r.getClass().getName() : source;
	}

	// ---- resolution ----------------------------------------------------------

	/**
	 * Pick a single value for a field. A curated value is authoritative and is
	 * never overridden by an API; API values only fill the gaps it leaves.
	 * Ties are broken by the value itself, so the outcome is reproducible rather
	 * than merely stable under insertion order.
	 */
	private <T> Optional<T> resolve(String field, Function<Record, Optional<T>> getter) {
		List<Map.Entry<Integer, T>> curated = new ArrayList<>();
		List<Map.Entry<Integer, T>> discovered = new ArrayList<>();
		for (Record record : records) {
			Optional<T> value = getter.apply(record);
			if (!value.isPresent() || value.get() == null) continue;
			Map.Entry<Integer, T> candidate = Map.entry(policy.rank(field, record), value.get());
			if (policy.isCurated(record)) curated.add(candidate); else discovered.add(candidate);
		}
		List<Map.Entry<Integer, T>> pool = curated.isEmpty() ? discovered : curated;
		return pool.stream()
				.min(Comparator.<Map.Entry<Integer, T>, Integer>comparing(Map.Entry::getKey)
						.thenComparing(entry -> String.valueOf(entry.getValue())))
				.map(Map.Entry::getValue);
	}

	/** contributing records in priority order, for the list-valued fields */
	private List<Record> byPriority(String field) {
		return records.stream()
				.sorted(Comparator.comparingInt((Record r) -> policy.rank(field, r))
						.thenComparing(MergePolicy::sourceOf)
						.thenComparing(r -> r.getIdentifier().orElse(""))
						.thenComparing(r -> r.getTitle().orElse("")))
				.collect(Collectors.toList());
	}

	// ---- scalar fields -------------------------------------------------------

	@Override
	public Optional<String> getAbstract() {
		return resolve(MergePolicy.ABSTRACT, Record::getAbstract);
	}

	@Override
	public Optional<String> getTitle() {
		return resolve(MergePolicy.TITLE, Record::getTitle);
	}

	@Override
	public Optional<String> getJournal() {
		return resolve(MergePolicy.CONTAINER, Record::getJournal);
	}

	@Override
	public Optional<LocalDate> getDate() {
		return resolve(MergePolicy.DATE, Record::getDate);
	}

	@Override
	public Optional<URI> getPdfUri() {
		return resolve(MergePolicy.PDF, Record::getPdfUri);
	}

	@Override
	public Optional<String> getPublicationType() {
		return resolve(MergePolicy.PUBLICATION_TYPE, Record::getPublicationType);
	}

	@Override
	public Optional<String> getPublisher() {
		return resolve(MergePolicy.PUBLISHER, Record::getPublisher);
	}

	// ---- identity ------------------------------------------------------------

	@Override
	public Set<ImmutableRecordReference> getIdentifiers() {
		return records.stream()
				.flatMap(r -> r.getIdentifiers().stream())
				.sorted(identifierOrder())
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static Comparator<ImmutableRecordReference> identifierOrder() {
		return Comparator
				.comparing((ImmutableRecordReference i) -> i.getIdentifierType().name())
				.thenComparing(i -> i.getIdentifier().orElse(""));
	}

	@Override
	public Optional<String> getIdentifier(IdType type) {
		return getIdentifiers().stream()
				.filter(i -> i.getIdentifierType().equals(type))
				.flatMap(i -> i.getIdentifier().stream())
				.findFirst();
	}

	@Override
	public Optional<String> getIdentifier() {
		return getIdentifier(IdType.DOI)
				.or(() -> getIdentifier(IdType.PMID))
				.or(() -> getIdentifier(IdType.PMCID).map(s -> s.replaceAll("^PMC", "")))
				.or(() -> getIdentifier(IdType.ISBN))
				// deterministic rather than whichever a hash happened to yield first
				.or(() -> getIdentifiers().stream().flatMap(i -> i.getIdentifier().stream()).findFirst());
	}

	@Override
	public IdType getIdentifierType() {
		if (getIdentifier(IdType.DOI).isPresent()) return IdType.DOI;
		if (getIdentifier(IdType.PMID).isPresent()) return IdType.PMID;
		if (getIdentifier(IdType.PMCID).isPresent()) return IdType.PMCID;
		if (getIdentifier(IdType.ISBN).isPresent()) return IdType.ISBN;
		return IdType.UNK;
	}

	@Override
	public List<RecordReference> getOtherIdentifiers() {
		return records.stream()
				.flatMap(r -> r.getOtherIdentifiers().stream())
				.sorted(identifierOrder())
				.distinct()
				.collect(Collectors.toList());
	}

	// ---- list-valued fields --------------------------------------------------

	@Override
	public List<? extends Author> getAuthors() {
		Set<String> seen = new LinkedHashSet<>();
		List<Author> out = new ArrayList<>();
		for (Record record : byPriority(MergePolicy.AUTHORS)) {
			for (Author author : record.getAuthors()) {
				if (author == null) continue;
				if (seen.add(authorKey(author))) out.add(author);
			}
		}
		return out;
	}

	/**
	 * ORCID when the source provides one, otherwise a normalised name. Name-only
	 * identity is best effort: the same person spelled differently in two
	 * vocabularies is not detectable without ORCID.
	 */
	private static String authorKey(Author author) {
		return author.getORCID()
				.map(orcid -> "orcid:" + orcid.trim().toLowerCase(Locale.ROOT))
				.orElseGet(() -> "name:" + (author.getLastName() + "|"
						+ author.getInitials().orElse("") + "|"
						+ author.getFirstName().orElse("")).trim().toLowerCase(Locale.ROOT));
	}

	@Override
	public Stream<String> getLicenses() {
		Set<String> distinct = new LinkedHashSet<>();
		for (Record record : byPriority(MergePolicy.LICENCES)) {
			record.getLicenses().forEach(distinct::add);
		}
		return distinct.stream();
	}

	@Override
	public Stream<? extends CitationLink> getCitations() {
		return byPriority(MergePolicy.CITATIONS).stream()
				.filter(RecordWithCitations.class::isInstance)
				.map(RecordWithCitations.class::cast)
				.flatMap(RecordWithCitations::getCitations);
	}

	// ---- provenance ----------------------------------------------------------

	@Override
	public Optional<Provenance> getProvenance() {
		return getProvenances().stream().findFirst();
	}

	/**
	 * Every contributing source, de-duplicated by source and ordered by source
	 * name, so provenance survives merging deterministically.
	 */
	public List<Provenance> getProvenances() {
		return records.stream()
				.flatMap(r -> r.getProvenance().stream())
				.sorted(Comparator.comparing(Provenance::getSource))
				.collect(Collectors.toMap(Provenance::getSource, p -> p, (a, b) -> a, LinkedHashMap::new))
				.values()
				.stream()
				.collect(Collectors.toList());
	}

	// ---- counts --------------------------------------------------------------

	@Override
	public Optional<Long> getCitedByCount() {
		return this.records.stream().flatMap(r -> r.getCitedByCount().stream()).max(Long::compare);
	}

	@Override
	public Optional<Long> getReferencesCount() {
		return this.records.stream().flatMap(r -> r.getReferencesCount().stream()).max(Long::compare);
	}

	public static Collector<Record,List<Record>,Optional<MergedRecord>> collector() {
		return new Collector<Record,List<Record>, Optional<MergedRecord>>() {
			@Override
			public Set<Characteristics> characteristics() {
				// safe to be unordered: resolution does not depend on insertion order
				return Set.of(Characteristics.UNORDERED,Characteristics.CONCURRENT);
			}
			@Override
			public Supplier<List<Record>> supplier() {
				return () -> new ArrayList<Record>();
			}
			@Override
			public BiConsumer<List<Record>, Record> accumulator() {
				return (l,r) -> l.add(r);
			}
			@Override
			public BinaryOperator<List<Record>> combiner() {
				return (l1,l2) -> {l1.addAll(l2); return l1;};
			}
			@Override
			public Function<List<Record>, Optional<MergedRecord>> finisher() {
				return l -> {
					if (l.isEmpty()) return Optional.empty();
					MergedRecord out = new MergedRecord();
					l.forEach(out::merge);
					return Optional.of(out);
				};
			}
		};
	}
}
