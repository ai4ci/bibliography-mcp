package uk.co.terminological.bibliography.api;

import java.util.List;

import uk.co.terminological.bibliography.record.Record;

/**
 * A record with the score that ordered it, and the APIs that found it.
 *
 * A wrapper rather than a field on {@link Record}, because a score belongs to a question and not to a work: the same
 * paper scores differently in two searches, and nothing about the paper changed. It exists at all because the score
 * was computed, used to sort, and then dropped at the return — so an agent saw an order it could not explain and had
 * no signal for "high value" at search time.
 *
 * {@code matchedApis} is the legible form of the same thing: three sources agreeing beats one, whatever the RRF float
 * happens to be.
 */
public record ScoredRecord(Record record, double score, List<String> matchedApis) {}
