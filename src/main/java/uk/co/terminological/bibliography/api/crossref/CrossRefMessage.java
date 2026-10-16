package uk.co.terminological.bibliography.api.crossref;

import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;

//When message-type is work-list
public class CrossRefMessage extends ExtensibleJson {
	public CrossRefMessage(JsonNode node) {
		super(node);
	}

	public Optional<String> getNextCursor() {
		return this.asString("next-cursor");
	}
	public Stream<CrossRefWork> getItems() {
		return this.streamNode(CrossRefWork.class, "items");
	}
	public Optional<Long> getTotalResults() {
		return this.asLong("total-results");
	}

}