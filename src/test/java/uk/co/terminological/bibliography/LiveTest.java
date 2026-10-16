package uk.co.terminological.bibliography;

/**
 * JUnit4 category marker for Tier-2 <em>live query</em> tests: tests that hit real bibliographic APIs to prove requests
 * still work and have not drifted. They are excluded from the default build (surefire {@code excludedGroups}) and run
 * on demand via {@code ./scripts/run-live-tests.sh}. Offline Tier-1 extraction tests are <em>not</em> tagged. See
 * plan-007-api-test-coverage.
 */
public interface LiveTest {
	// marker only
}
