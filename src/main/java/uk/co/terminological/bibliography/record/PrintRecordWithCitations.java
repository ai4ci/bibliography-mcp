package uk.co.terminological.bibliography.record;

import org.immutables.value.Value;

/**
 * All three at once: the claims about a work, its print coordinates, and the citations that arrived with it.
 *
 * The combination a source would implement if it returned everything in one payload. Nothing implements or consumes
 * it at present — see debt-011.
 */
@Value.Immutable
public interface PrintRecordWithCitations
		extends PrintRecord, RecordWithCitations {

}
