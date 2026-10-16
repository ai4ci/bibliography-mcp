package uk.co.terminological.bibliography.api.medrxiv;

import static uk.co.terminological.bibliography.record.Builder.recordReference;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.fluentxml.XmlAttribute;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class MedrxivSearchEntry implements Record, Raw<XmlElement> {

	private XmlElement raw; // PubmedArticle

	public MedrxivSearchEntry(XmlElement raw) {
		this.raw = raw;
	}

	@Override
	public Provenance getProvenance() {
		return Provenance.placeholder(
			this.getJournal()
				.orElse("unknown")
		);
	}

	@Override
	public Optional<String> getTitle() {
		try {
			return this.raw
				.doXpath(".//span[contains(@class,'highwire-cite-title')]")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent)
				.map(String::trim)
				.map(s -> s.replaceAll("\\s+", " "));
		} catch (XmlException e) {
			// log.debug("could not find title in entrez record");
			return Optional.empty();
		}
	}

	public Optional<String> getDoi() {
		try {
			var tmp = this.raw
				.doXpath(".//span[contains(@class,'highwire-cite-metadata-doi')]")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent)
				.map(String::trim);

			return tmp.map(
				s -> s.replace("doi:", "")
					.replace("https://doi.org/", "")
					.trim()
			);

		} catch (XmlException e) {
			// log.debug("could not find title in entrez record");
			return Optional.empty();
		}
	}

	/**
	 * This will perform require a redirect
	 * 
	 * @return
	 */
	@Override
	public Optional<URI> getPdfUri() {
		// https://www.medrxiv.org/content/10.1101/2021.01.18.21250041v6.full.pdf
		// https://www.biorxiv.org/content/10.1101/2021.08.10.455737v2.full.pdf
		if (!this.getJournal()
			.isPresent()) return Optional.empty();
		var journal = this.getJournal()
			.get();
		return this.getDoi()
			.map(d -> "https://www." + journal + ".org/content/" + d + ".full.pdf")
			.map(URI::create);
	}

	private Optional<String> getEncodedApath() {
		try {
			return this.raw.doXpath(".//@data-encoded-apath")
				.getMany(XmlAttribute.class)
				.stream()
				.map(
					a -> a.getValue()
						.replace(".atom", "")
				)
				.findFirst();
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	/**
	 * This will perform require a redirect
	 * 
	 * @return
	 */
	public Optional<URI> getJatsUri() {
		// https://www.medrxiv.org/content/early/2020/03/23/2020.03.20.20038406.source.xml
		// https://www.biorxiv.org/content/early/2018/08/28/282301.source.xml
		return this.raw.getAttributeValue("data-apath")
			.map(
				s -> s.replace(".atom", ".source.xml")
					.replace("/medrxiv/", "https://www.medrxiv.org/content/")
					.replace("/biorxiv/", "https://www.biorxiv.org/content/")
			)
			.map(URI::create);
	}

	@Override
	public List<Author> getAuthors() {
		try {
			return this.raw.doXpath(
				".//span[contains(@class,'highwire-citation-author') and not(contains(@class, 'highwire-citation-authors'))]"
			)
				.getManyAsStream(XmlElement.class)
				.map(MedrxivAuthor::new)
				.collect(Collectors.toList());
		} catch (XmlException e) {
			return Collections.emptyList();
		}
	}

	@Override
	public Optional<String> getIdentifier() { return this.getDoi(); }

	@Override
	public IdType getIdentifierType() { return IdType.DOI; }

	@Override
	public List<RecordReference> getOtherIdentifiers() {
		List<RecordReference> tmp = new ArrayList<>();
		this.getDoi()
			.map(d -> recordReference(IdType.DOI, d))
			.ifPresent(tmp::add);
		// getPMID().map(d -> recordReference(IdType.PMID,d)).ifPresent(tmp::add);
		return tmp;
	}

	@Override
	public Stream<String> getLicenses() { return Stream.empty(); }

	@Override
	public Optional<String> getJournal() {
		return this.getEncodedApath()
			.map(s -> {
				var tmp2 = s.split(";");
				return tmp2[1];
			});
	}

	@Override
	public XmlElement getRaw() { return this.raw; }

	@Override
	public Optional<String> getAbstract() { return Optional.empty(); }

	@Override
	public Optional<LocalDate> getDate() {
		return this.getEncodedApath()
			.map(
				s -> LocalDate.parse(
					extract(s, "[0-9]{4};[0-9]{2};[0-9]{2}").replace(";", "-")
				)
			);
	}

	private static String extract(String s, String regExp) {
		var pattern = Pattern.compile(regExp);
		var matcher = pattern.matcher(s);
		if (!matcher.find()) return null;
		return s.substring(matcher.start(), matcher.end());
	}

}