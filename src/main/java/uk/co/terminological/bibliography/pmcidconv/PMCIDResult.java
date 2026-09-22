package uk.co.terminological.bibliography.pmcidconv;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

//https://github.com/FasterXML/jackson-modules-java8
// ponytail: ignore unknown fields — idconv API now sends "response-date" (hyphenated)
@JsonIgnoreProperties(ignoreUnknown = true)
// ponytail: dropped unused responseDate/request fields — idconv API now sends "response-date"
// (hyphenated) and request as an object
public class PMCIDResult implements Iterable<PMCIDRecord> {
	@JsonProperty("status") public Optional<String> status = Optional.empty();
	@JsonProperty("records") public List<PMCIDRecord> records = Collections.emptyList();
	@Override
	
	public Iterator<PMCIDRecord> iterator() {
		return records.iterator();
	}
}