package uk.co.terminological.bibliography.europepmc;

import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import uk.co.terminological.bibliography.ExtensibleJson;

public class EuropePMCListResult<X extends ExtensibleJson> extends ExtensibleJson {

	Class<X> clazz;
	
	private EuropePMCListResult(Class<X> clazz, JsonNode raw) {
		super(raw);
		this.clazz = clazz;
	}
	
	// there paths are either/or in the output.
	public Stream<X> getItems() {
		return Stream.concat(Stream.concat(
				this.streamPath(clazz,"referenceList","reference"),
				this.streamPath(clazz,"citationList","citation")),
				this.streamPath(clazz,"resultList","result")
				);
		
	}
	
	public Optional<String> nextCursorMark() {
		return this.asString("nextCursorMark");}
	
	public Optional<Long> hitCount() {
		return this.asLong("hitCount");}

	/**
	 * Whether this is a complete Europe PMC list response.
	 *
	 * A truncated body — for example just {"version":"6.9"}, which is what a
	 * cut-short connection leaves behind — parses cleanly and yields an empty item
	 * stream, so without this check the client cannot tell "nothing was found" from
	 * "the response was broken", and the broken body then gets cached. A complete
	 * response always carries hitCount and/or the collection it fills.
	 *
	 * See issue-004 (truncated responses are cached as empty) in the design records:
	 * an IMPLEMENTS link is not valid from production code to an issue, so this is
	 * a prose reference.
	 */
	public boolean isWellFormed() {
		return hitCount().isPresent()
				|| getRaw().has("resultList")
				|| getRaw().has("referenceList")
				|| getRaw().has("citationList");
	}
	
	public Optional<Long> pageSize() {
		return this.streamPath("request","pageSize").findFirst().map(jn -> jn.asLong());}
	
	public Optional<Long> pageNumber() {
		return this.streamPath("request","page").findFirst().map(jn -> jn.asLong());}
	
	public static class Lite extends EuropePMCListResult<EuropePMCLiteResult> {
		public Lite(JsonNode raw) {
			super(EuropePMCLiteResult.class,raw);
		}
	}
	
	public static <Y extends ExtensibleJson> EuropePMCListResult<Y> empty(Class<Y> clz) {
		return new EuropePMCListResult<Y>(clz, JsonNodeFactory.instance.arrayNode());
	}
	
	
	public static class Core extends EuropePMCListResult<EuropePMCCoreResult> {
		public Core(JsonNode raw) {
			super(EuropePMCCoreResult.class,raw);
		}
	}
	
	public static class Reference extends EuropePMCListResult<uk.co.terminological.bibliography.europepmc.EuropePMCReference> {
		public Reference(JsonNode raw) {
			super(uk.co.terminological.bibliography.europepmc.EuropePMCReference.class,raw);
		}
	}
	
	public static class Citation extends EuropePMCListResult<uk.co.terminological.bibliography.europepmc.EuropePMCCitation> {
		public Citation(JsonNode raw) {
			super(uk.co.terminological.bibliography.europepmc.EuropePMCCitation.class,raw);
		}
	}
}
