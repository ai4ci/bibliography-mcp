package uk.co.terminological.bibliography.api.crossref;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;

public class CrossRefListResult extends ExtensibleJson {

	public CrossRefListResult(JsonNode node) {
		super(node);
	}

	public Optional<String> getStatus() { return this.asString("status"); }
	public CrossRefMessage getMessage() {
		return this.asObject(CrossRefMessage.class, "message")
			.get();
	}

}