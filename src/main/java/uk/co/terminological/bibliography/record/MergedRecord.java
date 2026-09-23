package uk.co.terminological.bibliography.record;

import java.io.Serializable;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
public class MergedRecord implements RecordWithCitations, Serializable {

	List<ImmutableRecord> records = new ArrayList<>();
	
	public static MergedRecord from(Record r) {
		if (r instanceof MergedRecord) return (MergedRecord) r;
		MergedRecord out = new MergedRecord();
		return out.merge(Builder.record(r));
	}
	
	public MergedRecord merge(Record r) {
		//TODO: Check for duplicates before merge
		records.add(Builder.record(r));
		return this;
	}
	
	public Set<ImmutableRecordReference> getIdentifiers() {
		return records.stream().flatMap(r -> r.getIdentifiers().stream()).collect(Collectors.toSet());
	}
	
	@Override
	public Optional<String> getIdentifier(IdType type) {
		return getIdentifiers().stream().filter(i -> i.getIdentifierType().equals(type)).flatMap(i -> i.getIdentifier().stream()).findFirst();
	}
	
	@Override
	public Optional<String> getIdentifier() {
		return getIdentifier(IdType.DOI)
				.or(() -> getIdentifier(IdType.PMID))
				.or(() -> getIdentifier(IdType.PMCID).map(s -> s.replaceAll("^PMC", "")))
				.or(() -> getIdentifier(IdType.ISBN))
				.or(() -> getIdentifiers().stream().flatMap(i -> i.getIdentifier().stream()).findFirst());
	}

	@Override
	public IdType getIdentifierType() {
		if(getIdentifier(IdType.DOI).isPresent()) return IdType.DOI;
		if(getIdentifier(IdType.PMID).isPresent()) return IdType.PMID;
		if(getIdentifier(IdType.PMCID).isPresent()) return IdType.PMCID;
		if(getIdentifier(IdType.ISBN).isPresent()) return IdType.ISBN;
		return IdType.UNK;
	}

	@Override
	public List<RecordReference> getOtherIdentifiers() {
		return records.stream().flatMap(r -> r.getOtherIdentifiers().stream()).collect(Collectors.toList());
	}

	@Override
	public List<? extends Author> getAuthors() {
		//TODO: resolve duplicates
		return records.stream().flatMap(r -> r.getAuthors().stream()).filter(s -> s != null).collect(Collectors.toList());
	}

	@Override
	public Stream<String> getLicenses() {
		return records.stream().flatMap(r -> r.getLicenses());
	}

	@Override
	public Optional<String> getAbstract() {
		return records.stream().flatMap(r -> r.getAbstract().stream()).filter(s -> s != null).findAny();
	}

	@Override
	public Optional<String> getTitle() {
		return records.stream().flatMap(r -> r.getTitle().stream()).filter(s -> s != null).findAny();
	}

	@Override
	public Optional<String> getJournal() {
		return records.stream().flatMap(r -> r.getJournal().stream()).filter(s -> s != null).findAny();
	}

	@Override
	public Optional<LocalDate> getDate() {
		return records.stream().flatMap(r -> r.getDate().stream()).filter(s -> s != null).findAny();
	}

	@Override
	public Optional<URI> getPdfUri() {
		return records.stream().flatMap(r -> r.getPdfUri().stream()).filter(s -> s != null).findAny();
	}

	// feat-006: first non-empty for now. feat-007 replaces these with the
	// documented per-field priority policy (plan-003) — do not rely on which
	// contributing record wins until then.
	@Override
	public Optional<String> getPublicationType() {
		return records.stream().flatMap(r -> r.getPublicationType().stream()).filter(s -> s != null).findFirst();
	}

	@Override
	public Optional<String> getPublisher() {
		return records.stream().flatMap(r -> r.getPublisher().stream()).filter(s -> s != null).findFirst();
	}

	@Override
	public Optional<Provenance> getProvenance() {
		return getProvenances().stream().findFirst();
	}

	/** Every contributing source, de-duplicated by source, so provenance survives merging. */
	public List<Provenance> getProvenances() {
		return records.stream()
				.flatMap(r -> r.getProvenance().stream())
				.collect(Collectors.toMap(Provenance::getSource, p -> p, (a, b) -> a, LinkedHashMap::new))
				.values()
				.stream()
				.collect(Collectors.toList());
	}

	public static Collector<Record,List<Record>,Optional<MergedRecord>> collector() {
		return new Collector<Record,List<Record>, Optional<MergedRecord>>() {
			@Override
			public Set<Characteristics> characteristics() {
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

	@Override
	public Stream<? extends CitationLink> getCitations() {
		return this.records.stream().flatMap(r -> r.getCitations());
	}

	@Override
	public Optional<Long> getCitedByCount() {
		return this.records.stream().flatMap(r -> r.getCitedByCount().stream()).max(Long::compare);
	}

	@Override
	public Optional<Long> getReferencesCount() {
		return this.records.stream().flatMap(r -> r.getReferencesCount().stream()).max(Long::compare);
	}

	
	
}
