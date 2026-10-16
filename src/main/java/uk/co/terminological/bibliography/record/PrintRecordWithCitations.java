package uk.co.terminological.bibliography.record;

import org.immutables.value.Value;

@Value.Immutable
public interface PrintRecordWithCitations
		extends PrintRecord, RecordWithCitations {

}
