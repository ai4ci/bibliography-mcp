package uk.co.terminological.bibliography.api.pmcidconv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;

// ponytail: ignore unknown fields — idconv API now sends "requested-id" and numbers for pmid
@JsonIgnoreProperties(ignoreUnknown = true)
public class PMCIDRecord {
	@JsonProperty("pmcid")
	public Optional<String> pmcid = Optional.empty();
	@JsonProperty("pmid")
	public Optional<String> pmid = Optional.empty();
	@JsonProperty("doi")
	public Optional<String> doi = Optional.empty();
	@JsonProperty("live")
	public Optional<Boolean> live = Optional.empty();
	@JsonProperty("status")
	public Optional<String> status = Optional.empty();
	@JsonProperty("errmsg")
	public Optional<String> errmsg = Optional.empty();
	@JsonProperty("versions")
	public List<PMCIDVersion> versions = Collections.emptyList();
	@JsonProperty("release-date")
	public Optional<String> releaseDate = Optional.empty();

	public boolean idNotFound() {
		return status.orElse("ok")
			.equals("error");
	}

	public Optional<String> idByType(IdType type) {
		if (type.equals(IdType.DOI)) return doi;
		if (type.equals(IdType.PMID)) return pmid;
		if (type.equals(IdType.PMCID)) return pmcid;
		return Optional.empty();
	}

	public List<ImmutableRecordReference> getMapping() {
		List<ImmutableRecordReference> out = new ArrayList<>();
		idByType(IdType.DOI)
			.ifPresent(s -> out.add(Builder.recordReference(IdType.DOI, s)));
		idByType(IdType.PMCID)
			.ifPresent(s -> out.add(Builder.recordReference(IdType.PMCID, s)));
		idByType(IdType.PMID)
			.ifPresent(s -> out.add(Builder.recordReference(IdType.PMID, s)));
		return out;
	}
}