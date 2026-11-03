package uk.co.terminological.bibliography.record;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.terminological.bibliography.api.crossref.CrossRefWork;

/**
 * debt-013: a citation that cannot be rendered says so.
 *
 * {@code render} answers an unrenderable record with an empty {@code Optional} and used to log nothing, so every
 * failure looked exactly like "this record has no citation". That silence hid issue-022 — a citation engine broken in
 * every built jar — behind a JSON field that was merely absent, for as long as nobody rendered one on the test
 * classpath.
 *
 * The {@code Optional} is deliberate and stays: callers depend on render being exception-free. The debt was the
 * silence.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestRenderFailsLoudly {

	/** catches what the code logs, so the assertion is about behaviour rather than about a console */
	static class Captured extends AppenderSkeleton {
		final List<LoggingEvent> events = new ArrayList<>();

		@Override
		protected void append(LoggingEvent event) { this.events.add(event); }
		@Override
		public void close() {}
		@Override
		public boolean requiresLayout() { return false; }

		List<String> messagesAtLeast(Level level) {
			var out = new ArrayList<String>();
			for (LoggingEvent e : this.events) {
				if (e.getLevel()
					.isGreaterOrEqual(level))
					out.add(String.valueOf(e.getMessage()));
			}
			return out;
		}
	}

	private Captured captured;

	@Before
	public void capture() {
		this.captured = new Captured();
		LogManager.getRootLogger()
			.addAppender(this.captured);
	}

	@After
	public void stopCapturing() {
		LogManager.getRootLogger()
			.removeAppender(this.captured);
	}

	private static CrossRefWork aWork() throws Exception {
		return new CrossRefWork(
				new ObjectMapper().readTree(
					"{\"DOI\":\"10.1136/bmjqs-2018-008370\",\"type\":\"journal-article\","
							+ "\"title\":[\"Artificial intelligence, bias and clinical safety\"],"
							+ "\"container-title\":[\"BMJ Quality & Safety\"],"
							+ "\"author\":[{\"given\":\"Robert\",\"family\":\"Challen\"}],"
							+ "\"issued\":{\"date-parts\":[[2019,1,12]]}}"
				)
		);
	}

	@Test
	public void aStyleThatCannotBeLoadedIsReportedRatherThanSwallowed()
			throws Exception {
		var rendered = aWork().render("not-a-real-csl-style");

		// the contract is unchanged: no exception, and an empty answer
		assertFalse(
			"an unloadable style should not produce a citation",
			rendered.isPresent()
		);
		// but it no longer looks like "this record has no citation"
		var warnings = this.captured.messagesAtLeast(Level.WARN);
		assertTrue(
			"nothing was logged about the failure: " + warnings,
			warnings.stream()
				.anyMatch(m -> m.contains("not-a-real-csl-style"))
		);
		assertTrue(
			"the message should name the record it failed on: " + warnings,
			warnings.stream()
				.anyMatch(m -> m.contains("10.1136/bmjqs-2018-008370"))
		);
	}

	@Test
	public void aRenderThatWorksSaysNothing() throws Exception {
		var rendered = aWork().render("ieee");

		assertTrue("ieee should render", rendered.isPresent());
		assertTrue(
			"a successful render should not warn: "
					+ this.captured.messagesAtLeast(Level.WARN),
			this.captured.messagesAtLeast(Level.WARN)
				.stream()
				.noneMatch(m -> m.contains("citation"))
		);
	}

	@Test
	public void noStyleIsNotAFailure() throws Exception {
		// render(null) is the plain-text form, not an error
		var rendered = aWork().render(null);

		assertTrue(rendered.isPresent());
		assertTrue(
			rendered.orElse(""), rendered.orElse("")
				.contains("Challen")
		);
		assertTrue(
			this.captured.messagesAtLeast(Level.WARN)
				.isEmpty()
		);
	}

	@Test
	public void aRecordWithNothingToCiteStillFailsByName() throws Exception {
		// an empty record is the other way render fails, and it must be as audible
		var bare = new CrossRefWork(new ObjectMapper().readTree("{}"));

		Optional<String> rendered = bare.render("ieee");

		if (rendered.isEmpty()) {
			assertTrue(
				"a failure on a record with no identifier should still be logged: "
						+ this.captured.messagesAtLeast(Level.WARN),
				this.captured.messagesAtLeast(Level.WARN)
					.stream()
					.anyMatch(m -> m.contains("ieee"))
			);
		}
	}

}
