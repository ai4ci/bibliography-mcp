package uk.co.terminological.bibliography.client;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.RecordReference;

public interface RecordFetcher {

	Map<ImmutableRecordReference, ? extends Record> fetch(
			Collection<? extends RecordReference> equivalentIds
	);

	default Optional<? extends Record> fetch(IdType type, String id) {
		ImmutableRecordReference tmp = Builder.recordReference(type, id);
		return fetch(Collections.singleton(tmp)).values()
			.stream()
			.collect(MergedRecord.collector());
	};

	default Optional<? extends Record> fetch(Record rec) {
		Set<RecordReference> tmp = new HashSet<>();
		tmp.add(rec);
		tmp.addAll(rec.getOtherIdentifiers());
		return fetch(tmp).values()
			.stream()
			.collect(MergedRecord.collector());
	};

}
