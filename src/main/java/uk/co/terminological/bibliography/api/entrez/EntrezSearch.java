package uk.co.terminological.bibliography.api.entrez;

import java.util.Optional;
import java.util.stream.Stream;

import javax.ws.rs.core.MultivaluedMap;

import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class EntrezSearch implements Raw<XmlElement> {

	private XmlElement raw;

	public EntrezSearch(XmlElement raw) {
		this.raw = raw;
	}

	public XmlElement getRaw() { return raw; }

	public Optional<Integer> count() {
		try {
			return raw.doXpath("/eSearchResult/Count")
				.getOne(XmlElement.class)
				.getTextContent()
				.map(s -> Integer.parseInt(s));
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Stream<String> getIds() {
		try {
			return raw.doXpath(".//Id")
				.getManyAsStream(XmlElement.class)
				.flatMap(
					o -> o.getTextContent()
						.stream()
				);
		} catch (XmlException e) {
			return Stream.empty();
		}
	}

	public Optional<String> getWebEnv() {
		try {
			return raw.doXpath(".//WebEnv")
				.getOne(XmlElement.class)
				.getTextContent();
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Optional<String> getQueryKey() {
		try {
			return raw.doXpath(".//QueryKey")
				.getOne(XmlElement.class)
				.getTextContent();
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Optional<EntrezEntries> getStoredResult(
			EntrezClient client, MultivaluedMap<String, String> searchParams
	) {
		Optional<String> webEnv = getWebEnv();
		Optional<String> queryKey = getQueryKey();
		if (webEnv.isPresent() && queryKey.isPresent()) {
			return client.getPMEntriesByWebEnvAndQueryKey(
				webEnv.get(),
				queryKey.get(),
				searchParams
			);
		}
		return Optional.empty();
	}

}