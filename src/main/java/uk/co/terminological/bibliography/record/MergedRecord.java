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

	List<ImmutableRecordWithCitations> records = new ArrayList<>();

	private final MergePolicy policy = MergePolicy.standard();

	public static MergedRecord from(Record r) {
		if (r instanceof MergedRecord) return (MergedRecord) r;
		var out = new MergedRecord();
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
		var candidate = Builder.recordWithCitations(r);
		// an exact re-contribution (same source, identifier and content) adds
		// nothing but memory, so repeated fetches do not inflate the merge
		if (this.records.stream()
			.noneMatch(existing -> sameContribution(existing, candidate))) {
			this.records.add(candidate);
		}
		return this;
	}

	private static boolean sameContribution(Record a, Record b) {
		return contributionKey(a).equals(contributionKey(b)) && a.getIdentifier()
			.equals(b.getIdentifier())
				&& a.getTitle()
					.equals(b.getTitle())
				&& a.getAbstract()
					.equals(b.getAbstract());
	}

	/** two records without provenance are not the same source just because both are unnamed */
	private static String contributionKey(Record r) {
		var source = MergePolicy.sourceOf(r);
		return source.isEmpty() ? r.getClass()
			.getName() : source;
	}

	// ---- resolution ----------------------------------------------------------

	/**
	 * Pick a single value for a field by the documented per-field priority.
	 *
	 * Every source is ranked the same way, including a Zotero item: a zotero record may be scraped from a publisher page
	 * and broken, or hand repaired, and nothing in the data reliably distinguishes the two. Where sources disagree the
	 * loser is not discarded — provenance and the contributing records are both retained so the disagreement can be
	 * reported (and, for write-back, refused rather than overwritten).
	 *
	 * Ties are broken by the value itself, so the outcome is reproducible rather than merely stable under insertion order.
	 */
	private <T> Optional<T> resolve(
			String field, Function<Record, Optional<T>> getter
	) {
		return this.records.stream()
			.map(
				record -> getter.apply(record)
					.filter(value -> value != null)
					.map(value -> Map.entry(this.policy.rank(field, record), value))
			)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.min(
				Comparator.<Map.Entry<Integer, T>, Integer>comparing(
					Map.Entry::getKey
				)
					.thenComparing(entry -> String.valueOf(entry.getValue()))
			)
			.map(Map.Entry::getValue);
	}

	/** contributing records in priority order, for the list-valued fields */
	private List<RecordWithCitations> byPriority(String field) {
		return this.records.stream()
			.sorted(
				Comparator.comparingInt((Record r) -> this.policy.rank(field, r))
					.thenComparing(MergePolicy::sourceOf)
					.thenComparing(
						r -> r.getIdentifier()
							.orElse("")
					)
					.thenComparing(
						r -> r.getTitle()
							.orElse("")
					)
			)
			.collect(Collectors.toList());
	}

	// ---- scalar fields -------------------------------------------------------

	@Override
	public Optional<String> getAbstract() {
		return this.resolve(MergePolicy.ABSTRACT, Record::getAbstract);
	}

	@Override
	public Optional<String> getTitle() {
		return this.resolve(MergePolicy.TITLE, Record::getTitle);
	}

	@Override
	public Optional<String> getJournal() {
		return this.resolve(MergePolicy.CONTAINER, Record::getJournal);
	}

	@Override
	public Optional<LocalDate> getDate() {
		return this.resolve(MergePolicy.DATE, Record::getDate);
	}

	@Override
	public Optional<URI> getPdfUri() {
		return this.resolve(MergePolicy.PDF, Record::getPdfUri);
	}

	@Override
	public Optional<String> getPublicationType() {
		return this
			.resolve(MergePolicy.PUBLICATION_TYPE, Record::getPublicationType);
	}

	@Override
	public Optional<String> getPublisher() {
		return this.resolve(MergePolicy.PUBLISHER, Record::getPublisher);
	}

	// ---- identity ------------------------------------------------------------

	@Override
	public Set<ImmutableRecordReference> getIdentifiers() {
		return this.records.stream()
			.flatMap(
				r -> r.getIdentifiers()
					.stream()
			)
			.sorted(identifierOrder())
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static Comparator<RecordReference> identifierOrder() {
		return Comparator.comparing(
			(RecordReference i) -> i.getIdentifierType()
				.name()
		)
			.thenComparing(
				i -> i.getIdentifier()
					.orElse("")
			);
	}

	@Override
	public Optional<String> getIdentifier(IdType type) {
		return this.getIdentifiers()
			.stream()
			.filter(
				i -> i.getIdentifierType()
					.equals(type)
			)
			.flatMap(
				i -> i.getIdentifier()
					.stream()
			)
			.findFirst();
	}

	@Override
	public Optional<String> getIdentifier() {
		return this.getIdentifier(IdType.DOI)
			.or(() -> this.getIdentifier(IdType.PMID))
			.or(
				() -> this.getIdentifier(IdType.PMCID)
					.map(s -> s.replaceAll("^PMC", ""))
			)
			.or(() -> this.getIdentifier(IdType.ISBN))
			// deterministic rather than whichever a hash happened to yield first
			.or(
				() -> this.getIdentifiers()
					.stream()
					.flatMap(
						i -> i.getIdentifier()
							.stream()
					)
					.findFirst()
			);
	}

	@Override
	public IdType getIdentifierType() {
		if (this.getIdentifier(IdType.DOI)
			.isPresent()) return IdType.DOI;
		if (this.getIdentifier(IdType.PMID)
			.isPresent()) return IdType.PMID;
		if (this.getIdentifier(IdType.PMCID)
			.isPresent()) return IdType.PMCID;
		if (this.getIdentifier(IdType.ISBN)
			.isPresent()) return IdType.ISBN;
		return IdType.UNK;
	}

	@Override
	public List<RecordReference> getOtherIdentifiers() {
		return this.records.stream()
			.flatMap(
				r -> r.getOtherIdentifiers()
					.stream()
			)
			.sorted(identifierOrder())
			.distinct()
			.collect(Collectors.toList());
	}

	// ---- list-valued fields --------------------------------------------------

	@Override
	public List<Author> getAuthors() {
		Set<String> seen = new LinkedHashSet<>();
		List<Author> out = new ArrayList<>();
		for (Record record : this.byPriority(MergePolicy.AUTHORS)) {
			for (Author author : record.getAuthors()) {
				if (author == null) { continue; }
				if (seen.add(authorKey(author))) { out.add(author); }
			}
		}
		return out;
	}

	/**
	 * ORCID when the source provides one, otherwise a normalised name. Name-only identity is best effort: the same person
	 * spelled differently in two vocabularies is not detectable without ORCID.
	 */
	private static String authorKey(Author author) {
		return author.getORCID()
			.map(
				orcid -> "orcid:" + orcid.trim()
					.toLowerCase(Locale.ROOT)
			)
			.orElseGet(
				() -> "name:" + (author.getLastName() + "|" + author.getInitials()
					.orElse("") + "|"
						+ author.getFirstName()
							.orElse(""))
					.trim()
					.toLowerCase(Locale.ROOT)
			);
	}

	@Override
	public Stream<String> getLicenses() {
		Set<String> distinct = new LinkedHashSet<>();
		for (Record record : this.byPriority(MergePolicy.LICENCES)) {
			record.getLicenses()
				.forEach(distinct::add);
		}
		return distinct.stream();
	}

	@Override
	public List<CitationLink> getCitations() {
		return this.byPriority(MergePolicy.CITATIONS)
			.stream()
			.flatMap(
				c -> c.getCitations()
					.stream()
			)
			.collect(Collectors.toList());

	}

	// ---- provenance ----------------------------------------------------------

	@Override
	public Provenance getProvenance() { return Provenance.curated("merged"); }

	/**
	 * Every contributing source, de-duplicated by source and ordered by source name, so provenance survives merging
	 * deterministically.
	 */
	public List<Provenance> getProvenances() {
		return this.records.stream()
			.map(ImmutableRecordWithCitations::getProvenance)
			.sorted(Comparator.comparing(Provenance::getSource))
			.collect(
				Collectors.toMap(
					Provenance::getSource,
					p -> p,
					(a, b) -> a,
					LinkedHashMap::new
				)
			)
			.values()
			.stream()
			.collect(Collectors.toList());
	}

	// ---- counts --------------------------------------------------------------

	@Override
	public Optional<Long> getCitedByCount() {
		return this.records.stream()
			.filter(RecordWithCitations.class::isInstance)
			.map(RecordWithCitations.class::cast)
			.flatMap(
				r -> r.getCitedByCount()
					.stream()
			)
			.max(Long::compare);
	}

	@Override
	public Optional<Long> getReferencesCount() {
		return this.records.stream()
			.filter(RecordWithCitations.class::isInstance)
			.map(RecordWithCitations.class::cast)
			.flatMap(
				r -> r.getReferencesCount()
					.stream()
			)
			.max(Long::compare);
	}

	public static Collector<Record, List<Record>, Optional<MergedRecord>> collector() {
		return new Collector<>() {
			@Override
			public Set<Characteristics> characteristics() {
				// safe to be unordered: resolution does not depend on insertion order
				return Set
					.of(Characteristics.UNORDERED, Characteristics.CONCURRENT);
			}
			@Override
			public Supplier<List<Record>> supplier() {
				return ArrayList::new;
			}
			@Override
			public BiConsumer<List<Record>, Record> accumulator() {
				return List::add;
			}
			@Override
			public BinaryOperator<List<Record>> combiner() {
				return (l1, l2) -> {
					l1.addAll(l2);
					return l1;
				};
			}
			@Override
			public Function<List<Record>, Optional<MergedRecord>> finisher() {
				return l -> {
					if (l.isEmpty()) return Optional.empty();
					var out = new MergedRecord();
					l.forEach(out::merge);
					return Optional.of(out);
				};
			}
		};
	}
}
