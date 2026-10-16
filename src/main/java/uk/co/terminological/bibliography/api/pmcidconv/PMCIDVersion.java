package uk.co.terminological.bibliography.api.pmcidconv;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PMCIDVersion {
	@JsonProperty("pmcid")
	public Optional<String> pmcid = Optional.empty();
	@JsonProperty("mid")
	public Optional<String> pmid = Optional.empty();
	@JsonProperty("current")
	public Optional<Boolean> current = Optional.empty();
	@JsonProperty("live")
	public Optional<Boolean> live = Optional.empty();
	@JsonProperty("release-date")
	public Optional<String> releaseDate = Optional.empty();
}