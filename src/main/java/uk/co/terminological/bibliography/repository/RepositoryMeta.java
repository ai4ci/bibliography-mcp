package uk.co.terminological.bibliography.repository;

import java.time.Instant;

/**
 * What the repository says about itself: the schema it was written with, and when it was created.
 *
 * The schema version is the point. A store that cannot say which version wrote it can only fail by surprise later, one
 * field at a time.
 */
public record RepositoryMeta(int schemaVersion, Instant createdAt) {}
