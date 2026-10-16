package uk.co.terminological.bibliography.api.unpaywall;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;

public class UnpaywallAuthor extends ExtensibleJson
		implements uk.co.terminological.bibliography.record.Author {

	public UnpaywallAuthor(JsonNode node) {
		super(node);
	}

	@Override
	public String getLastName() {
		return this.asString("family")
			.or(() -> this.asString("name"))
			.or(
				() -> this.rawAuthor()
					.reversed()
					.stream()
					.findFirst()
			)
			.orElse("Unknown");
	}
	@Override
	public Optional<String> getORCID() { return this.asString("ORCID"); }
	@Override
	public Stream<String> getAffiliations() {
		return this.streamPath("affiliation", "name")
			.map(ExtensibleJson::asString);
	}
	@Override
	public Optional<String> getFirstName() {
		return this.asString("given")
			.or(
				() -> this.rawAuthor()
					.stream()
					.findFirst()
			);
	}

	public boolean isFirst() {
		return this.asString("sequence")
			.filter(s -> s.equals("first"))
			.isPresent();
	}

	@Override
	public Optional<String> getInitials() {
		return this.getFirstName()
			.map(s -> s.substring(0, 1));
	}

	private List<String> rawAuthor() {
		return Arrays.asList(
			this.asString("raw_author_name")
				.map(s -> s.split("\\s"))
				.orElse(new String[] {})
		);
	}

}