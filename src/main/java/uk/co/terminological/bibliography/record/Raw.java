package uk.co.terminological.bibliography.record;

/**
 * provides access to the low level response of a bibliographic api
 */
public interface Raw<X> {
	// TODO: add this interface in implementation of all client data objects
	X getRaw();
}
