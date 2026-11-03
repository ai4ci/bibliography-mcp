package uk.co.terminological.bibliography.repository;

/**
 * A repository that cannot be read, and why.
 *
 * Unchecked because nothing a caller does recovers from it: the file is from a version this build does not know, or the
 * database refused the write. What matters is that the message names the thing — the schema version, the field — rather
 * than a {@code serialVersionUID}, which is what made debt-001's failures impossible to act on.
 */
public class RepositoryException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public RepositoryException(String message) { super(message); }

	public RepositoryException(String message, Throwable cause) {
		super(message, cause);
	}

}
