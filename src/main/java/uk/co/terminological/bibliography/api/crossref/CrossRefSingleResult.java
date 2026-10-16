package uk.co.terminological.bibliography.api.crossref;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.record.Raw;

public class CrossRefSingleResult extends ExtensibleJson
		implements Raw<JsonNode> {
	public CrossRefSingleResult(JsonNode node) {
		super(node);
	}

	public Optional<String> getStatus() { return this.asString("status"); }
	public CrossRefWork getWork() {
		return this.asObject(CrossRefWork.class, "message")
			.get();
	}

}