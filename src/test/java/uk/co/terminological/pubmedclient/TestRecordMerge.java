package uk.co.terminological.pubmedclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.Test;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * Reproduces issue-003: merged records are neither robust nor predictable.
 * These assertions pin the *current* (wrong) behaviour so the fix has a
 * failing-then-passing target. Rewrite them in feat-007 / plan-003.
 *
 * [TESTS](/design/implementation/issues/issue-003-merged-record-resolution-is-non-deterministic.md)
 */
public class TestRecordMerge {

	@Test
	public void mergedFieldSelectionDependsOnInsertionOrder() {
		Record a = new StubRecord("10.1/x", "Title from A", List.of());
		Record b = new StubRecord("10.1/x", "Title from B", List.of());

		// the same two records, merged in opposite orders
		String ab = MergedRecord.from(a).merge(b).getTitle().orElse(null);
		String ba = MergedRecord.from(b).merge(a).getTitle().orElse(null);

		assertEquals("Title from A", ab);
		assertEquals("Title from B", ba);
		// order-dependence proves there is no precedence rule. feat-007 must make
		// both expressions return the same, documented value.
	}

	@Test
	public void mergedAuthorsAccumulateRatherThanDeduplicate() {
		Record a = new StubRecord("10.1/y", "Same work", List.of(new StubAuthor("Smith")));
		Record b = new StubRecord("10.1/y", "Same work", List.of(new StubAuthor("Smith")));

		MergedRecord merged = MergedRecord.from(a).merge(b);

		assertEquals(2, merged.getAuthors().size());
		// the same author reported twice; feat-007 must de-duplicate
	}

	@Test
	public void identifiersAreUnited() {
		Record a = new StubRecord("10.1/z", "Work", List.of());
		Record b = new StubRecord("10.1/z", "Work", List.of());

		assertTrue(MergedRecord.from(a)
			.merge(b)
			.getIdentifiers()
			.stream()
			.anyMatch(i -> i.getIdentifierType() == IdType.DOI));
	}

	// --- minimal stand-ins so the merge behaviour can be exercised offline ---

	static class StubAuthor implements Author {
		private final String lastName;

		StubAuthor(String lastName) { this.lastName = lastName; }

		public Optional<String> getORCID() { return Optional.empty(); }
		public Optional<String> getFirstName() { return Optional.of("A"); }
		public String getLastName() { return lastName; }
		public Optional<String> getInitials() { return Optional.of("A"); }
		public Stream<String> getAffiliations() { return Stream.empty(); }
	}

	static class StubRecord implements Record {
		private final String id;
		private final String title;
		private final List<Author> authors;

		StubRecord(String id, String title, List<Author> authors) {
			this.id = id;
			this.title = title;
			this.authors = authors;
		}

		public Optional<String> getIdentifier() { return Optional.of(id); }
		public IdType getIdentifierType() { return IdType.DOI; }
		public List<? extends RecordReference> getOtherIdentifiers() { return List.of(); }
		public List<? extends Author> getAuthors() { return authors; }
		public Stream<String> getLicenses() { return Stream.empty(); }
		public Optional<String> getAbstract() { return Optional.empty(); }
		public Optional<String> getTitle() { return Optional.of(title); }
		public Optional<String> getJournal() { return Optional.empty(); }
		public Optional<LocalDate> getDate() { return Optional.empty(); }
		public Optional<URI> getPdfUri() { return Optional.empty(); }
	}
}
