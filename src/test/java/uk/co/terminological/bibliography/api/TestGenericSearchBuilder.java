package uk.co.terminological.bibliography.api;

import static org.junit.Assert.assertSame;

import org.junit.Test;

import uk.co.terminological.bibliography.client.ImmutableQuery;
import uk.co.terminological.bibliography.client.Query;

/**
 * The facade's own {@link uk.co.terminological.bibliography.client.SearchBuilder}.
 * <p>
 * Asserts the builder holds what it was given. The field is read directly because the builder has no accessor and the
 * only alternative — calling {@code execute()} — needs a live facade, which would make a one-line contract depend on
 * the network (issue-017).
 */
public class TestGenericSearchBuilder {

	@Test
	public void withQueryKeepsTheQueryItWasGiven() {
		Query qry = ImmutableQuery.builder()
			.allFields("photosynthesis")
			.build();
		BiblioFacade.GenericSearchBuilder builder = new BiblioFacade.GenericSearchBuilder(
				null
		);
		assertSame(qry, builder.withQuery(qry).qry);
	}

}
