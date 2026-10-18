package uk.co.terminological.bibliography.api.entrez;

import static uk.co.terminological.bibliography.record.Builder.recordReference;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.PrintRecord;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;
import uk.co.terminological.fluentxml.XmlList;

// [IMPLEMENTS](/design/features/feat-007-predictable-record-merging.md)
public class EntrezEntry implements PrintRecord, Raw<XmlElement> {

	// provenance lets the merge policy rank this source; the native publication type is not parsed yet
	@Override
	public Provenance getProvenance() {
		return Provenance.canonical(Provenance.CROSSREF, Optional.empty());
	}

	private XmlElement raw; // PubmedArticle

	public EntrezEntry(XmlElement raw) {
		this.raw = raw;
	}

	@Override
	public XmlElement getRaw() { return this.raw; }

	@Override
	public Optional<String> getTitle() {
		try {
			return this.raw.doXpath(".//ArticleTitle")
				.getOne(XmlElement.class)
				.getTextContent();
		} catch (XmlException e) {
			// log.debug("could not find title in entrez record");
			return Optional.empty();
		}
	}

	public Optional<String> getDoi() {
		try {
			return this.raw.doXpath(".//ArticleId[@IdType='doi']")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Optional<String> getPMCID() {
		try {
			return this.raw.doXpath(".//ArticleId[@IdType='pmc']")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
		} catch (XmlException e) {
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
		return this.getPMCID()
			.map(s -> "https://www.ncbi.nlm.nih.gov/pmc/articles/" + s + "/pdf")
			.map(URI::create);
	}

	public Optional<String> getPMID() {
		try {
			return this.raw.doXpath(".//ArticleId[@IdType='pubmed']")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public List<String> getKeyWords() {
		try {
			XmlList<XmlElement> keywords = this.raw
				.doXpath(".//KeywordList/Keyword")
				.getMany(XmlElement.class);
			return keywords.stream()
				.flatMap(
					xmlt -> xmlt.getTextContent()
						.stream()
				)
				.collect(Collectors.toList());
		} catch (XmlException e) {
			return Collections.emptyList();
		}
	}

	public List<String> getCitedPMIDs() {
		try {
			XmlList<XmlElement> keywords = this.raw
				.doXpath(".//CommentsCorrections[@RefType='Cites']/PMID")
				.getMany(XmlElement.class);
			return keywords.stream()
				.flatMap(
					xmlt -> xmlt.getTextContent()
						.stream()
				)
				.collect(Collectors.toList());
		} catch (XmlException e) {
			return Collections.emptyList();
		}
	}

	@Override
	public Optional<LocalDate> getDate() {
		try {
			Optional<XmlElement> date = this.raw.doXpath(".//Article/ArticleDate")
				.get(XmlElement.class);
//			if (date.isEmpty()) {
//				date = this.raw.doXpath(".//Article/Journal/JournalIssue/PubDate")
//					.get(XmlElement.class);
//			}
//			`PubDate` needs care: it is not always a full date. It can be a year alone, a
//			year plus a month name (`2019 Jun`), or a `MedlineDate` string such as
//			`2019 Jun-Jul`
			if (date.isEmpty()) {
				date = this.raw.doXpath(".//PubMedPubDate[@PubStatus='pubmed']")
					.get(XmlElement.class);
			}
			return date.flatMap(el -> {
				Optional<Integer> year = el.childElements("Year")
					.findFirst()
					.flatMap(XmlElement::getTextContent)
					.map(Integer::parseInt);
				Optional<Integer> month = el.childElements("Month")
					.findFirst()
					.flatMap(XmlElement::getTextContent)
					.map(Integer::parseInt);
				Optional<Integer> day = el.childElements("Day")
					.findFirst()
					.flatMap(XmlElement::getTextContent)
					.map(Integer::parseInt);
				return year
					.map(y -> LocalDate.of(y, month.orElse(1), day.orElse(1)));
			});
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Stream<EntrezMeshHeading> getMeshHeadings() {
		try {
			return this.raw.doXpath(".//MeshHeading")
				.getManyAsStream(XmlElement.class)
				.map(EntrezMeshHeading::new);
		} catch (XmlException e) {
			return Stream.empty();
		}
	}

	@Override
	public Optional<String> getAbstract() { return this.getAbstract(true); }
	public Optional<String> getUnlabelledAbstract() {
		return this.getAbstract(false);
	}

	private Optional<String> getAbstract(final boolean labelled) {
		try {
			var tmp = this.raw.doXpath(".//AbstractText")
				.getManyAsStream(XmlElement.class)
				.map(el -> {
					if (labelled)
						return el.getAttributeValue("label")
							.map(o -> o + "\t")
							.orElse("")
								+ el.getTextContent()
									.orElse("");
					else
						return el.getTextContent()
							.orElse("");
				})
				.collect(Collectors.joining("\n"));
			return tmp.isEmpty() ? Optional.empty() : Optional.of(tmp);
		} catch (XmlException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<Author> getAuthors() {
		try {
			return this.raw.doXpath(".//Author")
				.getManyAsStream(XmlElement.class)
				.map(EntrezAuthor::new)
				.collect(Collectors.toList());
		} catch (XmlException e) {
			return Collections.emptyList();
		}
	}

	@Override
	public Optional<String> getIdentifier() {
		return this.getPMID()
			.map(s -> s.replaceAll("^PMC", ""));
	}

	@Override
	public IdType getIdentifierType() { return IdType.PMID; }

	@Override
	public List<RecordReference> getOtherIdentifiers() {
		List<RecordReference> tmp = new ArrayList<>();
		this.getDoi()
			.map(d -> recordReference(IdType.DOI, d))
			.ifPresent(tmp::add);
		this.getPMCID()
			.map(d -> recordReference(IdType.PMCID, d))
			.ifPresent(tmp::add);
		// getPMID().map(d -> recordReference(IdType.PMID,d)).ifPresent(tmp::add);
		return tmp;
	}

	@Override
	public Stream<String> getLicenses() { return Stream.empty(); }

	@Override
	public Optional<String> getJournal() {
		try {
			return this.raw.doXpath(".//Article/Journal/ISOAbbreviation")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<String> getFirstAuthorName() {
		return this.getFirstAuthor()
			.map(Author::getLastName);
	}

	@Override
	public Optional<String> getVolume() {
		try {
			return this.raw.doXpath(".//Article/Journal/JournalIssue/Volume")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<String> getIssue() {
		try {
			return this.raw.doXpath(".//Article/Journal/JournalIssue/Issue")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<Long> getYear() {
		try {
			return this.raw.doXpath(".//Article/Journal/JournalIssue/PubDate/Year")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent)
				.map(Long::parseLong);
		} catch (XmlException e) {
			return Optional.empty();
		}

	}

	@Override
	public Optional<String> getPage() {
		try {
			return this.raw.doXpath(".//Article/Pagination/MedlinePgn")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

}