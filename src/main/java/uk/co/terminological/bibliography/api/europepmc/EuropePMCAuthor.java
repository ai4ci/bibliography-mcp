package uk.co.terminological.bibliography.api.europepmc;

import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.record.Author;

public class EuropePMCAuthor extends ExtensibleJson implements Author {

	/*
	 * authorList author fullName firstName lastName initials authorId affiliation affiliationOrgId
	 */

	public EuropePMCAuthor(JsonNode node) {
		super(node);
	}

	@Override
	public Optional<String> getORCID() {
		return this.streamPath("authorId", "value")
			.findFirst()
			.map(n -> n.asString());
	}

	@Override
	public Optional<String> getFirstName() { return this.asString("firstName"); }

	@Override
	public String getLastName() {
		return this.asString("lastName")
			.orElse(
				this.asString("fullName")
					.orElse("Unknown")
			);
	}

	@Override
	public Optional<String> getInitials() { return this.asString("initials"); }

	@Override
	public Stream<String> getAffiliations() {
		return this.asString("affiliation")
			.stream();
	}

}
