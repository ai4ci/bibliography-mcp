package uk.co.terminological.bibliography.api.entrez;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.api.entrez.EntrezClient.Database;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.datatypes.FluentMap;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class EntrezLinks implements Raw<XmlElement> {

	private XmlElement raw;
	public EntrezLinks(XmlElement raw) throws XmlException {
		this.raw = raw;
		this.convert();
	}
	@Override
	public XmlElement getRaw() { return this.raw; }

	private List<EntrezLink> links;
	private static Map<String, IdType> lookup = FluentMap
		.with(Database.PUBMED.toString(), IdType.PMID)
		.and(Database.PMC.toString(), IdType.PMCID);

	public Stream<EntrezLink> stream() {
		return this.links.stream();
	}

	private void convert() throws XmlException {
		this.links = new ArrayList<>();

		for (XmlElement linkSet : this.raw.doXpath(".//LinkSet")
			.getMany(XmlElement.class)) {

			Optional<String> dbFrom = linkSet.doXpath("./DbFrom")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
			Optional<String> fromId = linkSet.doXpath("./IdList/Id")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);

			for (XmlElement linkSetDb : linkSet.doXpath("./LinkSetDb")
				.getMany(XmlElement.class)) {

				Optional<String> dbTo = linkSetDb.doXpath("./DbTo")
					.get(XmlElement.class)
					.flatMap(XmlElement::getTextContent);
				Optional<String> linkName = linkSetDb.doXpath("./LinkName")
					.get(XmlElement.class)
					.flatMap(XmlElement::getTextContent);

				for (XmlElement link : linkSetDb.doXpath("./Link")
					.getMany(XmlElement.class)) {

					Optional<String> toId = link.doXpath("./Id")
						.get(XmlElement.class)
						.flatMap(XmlElement::getTextContent);
					Optional<Long> score = link.doXpath("./Score")
						.get(XmlElement.class)
						.flatMap(XmlElement::getTextContent)
						.map(Long::parseLong);

					if (dbFrom.isPresent() && fromId.isPresent()
							&& toId.isPresent()) {
						this.links.add(
							new EntrezLink(
									dbFrom.get(), fromId.get(), linkName, dbTo,
									toId.get(), score
							)
						);
					}

				}

			}

			for (XmlElement idUrlSet : linkSet.doXpath("./IdUrlList/IdUrlSet")
				.getMany(XmlElement.class)) {

				fromId = idUrlSet.doXpath("./Id")
					.get(XmlElement.class)
					.flatMap(XmlElement::getTextContent);
				for (XmlElement objUrl : idUrlSet.doXpath("./ObjUrl")
					.getMany(XmlElement.class)) {

					Optional<String> category = objUrl.doXpath("./Category")
						.get(XmlElement.class)
						.flatMap(XmlElement::getTextContent);
					Optional<String> toUrl = objUrl.doXpath("./Url")
						.get(XmlElement.class)
						.flatMap(XmlElement::getTextContent);

					if (dbFrom.isPresent() && fromId.isPresent()
							&& toUrl.isPresent()) {
						this.links.add(
							new EntrezLink(
									dbFrom.get(), fromId.get(), category, toUrl.get()
							)
						);
					}
				}
			}

		}

	}
	public List<EntrezLink> getLinks() { return this.links; }

	public Stream<CitationLink> getCitations(boolean invert) {
		return this.stream()
			.filter(l -> l.toId.isPresent())
			.map(l -> {

				var rhs = Builder
					.recordReference(lookup.get(l.toDbOrUrl), l.toId.get());
				var lhs = Builder.recordReference(lookup.get(l.fromDb), l.fromId);

				if (invert)
					return Builder.citationLink(rhs, lhs, null);
				else
					return Builder.citationLink(lhs, rhs, null);
			});

	}

	// public List<String> getSimilar

}