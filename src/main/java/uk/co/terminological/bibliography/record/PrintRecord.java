package uk.co.terminological.bibliography.record;

import org.immutables.value.Value;

/**
 * A record from a source that also gives its print coordinates.
 *
 * Nothing more than the intersection of {@link Record} and {@link PrintReference}: the claims about the work, plus
 * where it sits in a journal. Implemented by the sources whose payloads carry pagination — {@code EntrezEntry} and
 * {@code EuropePMCCoreResult}. It exists so those extra fields are reachable without an {@code instanceof}, and so
 * that a record which can be rendered as a full citation is distinguishable at the type level from one which cannot.
 */
@Value.Immutable
public interface PrintRecord extends PrintReference, Record {

}
