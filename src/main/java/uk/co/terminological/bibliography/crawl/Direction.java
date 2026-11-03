package uk.co.terminological.bibliography.crawl;

/**
 * Which way a crawl follows citations.
 *
 * {@code DOWN} follows what a work cites — a reference list, finite and known at publication. {@code UP} follows what
 * cites it, which is open-ended and grows forever. {@code BOTH} does the two and counts as <em>one</em> hop, because a
 * caller asking for depth 2 in both directions means two hops of a wider graph, not four.
 */
public enum Direction {
	UP, DOWN, BOTH;

	public boolean follows(Direction other) {
		return this == BOTH || this == other;
	}
}
