package uk.co.terminological.bibliography.api.crossref;

import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.record.Author;

public class CrossRefContributor extends ExtensibleJson implements Author {

	public CrossRefContributor(JsonNode node) {
		super(node);
	}

	public String getLastName() {
		return this.asString("family")
			.orElse("Unknown");
	}
	public Optional<String> getORCID() { return this.asString("ORCID"); }
	public Stream<String> getAffiliations() {
		return this.streamNode("affiliation")
			.flatMap(
				n -> n.asString("name")
					.stream()
			);
	}
	public Optional<String> getFirstName() { return this.asString("given"); }
	public boolean isFirst() {
		return this.asString("sequence")
			.filter(s -> s.equals("first"))
			.isPresent();
	}

	@Override
	public Optional<String> getInitials() {
		return getFirstName().map(o -> o.substring(0, 1));
	}
}