package uk.co.terminological.bibliography.jats;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableCitationLink;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordWithCitations;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;
import uk.co.terminological.fluentxml.XmlList;

public class JatsEntry implements RecordWithCitations, Raw<XmlElement> {

	private XmlElement raw; // PubmedArticle
	Provenance prov;

	public JatsEntry(XmlElement raw, Provenance prov) {
		this.raw = raw;
		this.prov = prov;
	}

	@Override
	public Provenance getProvenance() { return this.prov; }

	@Override
	public XmlElement getRaw() { return this.raw; }

	@Override
	public Optional<String> getTitle() {
		try {
			// scoped to the front matter: a JATS reference list also contains
			// <article-title>, so an unscoped match finds the wrong thing or several
			return this.raw.doXpath(".//front//article-title")
				.getManyAsStream(XmlElement.class)
				.flatMap(
					el -> el.getTextContent()
						.stream()
				)
				.findFirst();
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Optional<String> getDoi() {
		try {
			return this.raw.doXpath(".//article-id[@pub-id-type='doi']")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Optional<String> getPMCID() {
//		try {
//			return raw.doXpath(".//ArticleId[@IdType='pmc']").get(XmlElement.class).flatMap(o -> o.getTextContent());
//		} catch (XmlException e) {
		return Optional.empty();
//		}
	}

	/**
	 * This will perform require a redirect
	 * 
	 * @return
	 */
	// public abstract Optional<URI> getPdfUri();

	public Optional<String> getPMID() { return Optional.empty(); }

	public List<String> getKeyWords() {
		try {
			XmlList<XmlElement> keywords = this.raw
				.doXpath(".//article-categories//subject")
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
			Optional<XmlElement> date = this.raw
				.doXpath(".//pub-date[@pub-type='epub']")
				.get(XmlElement.class);
			return date.flatMap(el -> {
				Optional<Integer> year = el.childElements("year")
					.findFirst()
					.flatMap(XmlElement::getTextContent)
					.map(Integer::parseInt);
				Optional<Integer> month = el.childElements("month")
					.findFirst()
					.flatMap(XmlElement::getTextContent)
					.map(Integer::parseInt);
				Optional<Integer> day = el.childElements("day")
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

	@Override
	public Optional<String> getAbstract() { return this.getAbstract(true); }
	public Optional<String> getUnlabelledAbstract() {
		return this.getAbstract(false);
	}

	private Optional<String> getAbstract(final boolean labelled) {
		try {
			var tmp = this.raw.doXpath(".//front//abstract")
				.getManyAsStream(XmlElement.class)
				.map(
					el -> el.getTextContent()
						.orElse("")
				)
				.collect(Collectors.joining("\n"));
			return tmp.isEmpty() ? Optional.empty() : Optional.of(tmp);
		} catch (XmlException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<Author> getAuthors() {
		try {
			return this.raw.doXpath(".//contrib[@contrib-type='author']")
				.getManyAsStream(XmlElement.class)
				.map(JatsAuthor::new)
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
		// getDoi().map(d -> recordReference(IdType.DOI,d)).ifPresent(tmp::add);
		// getPMCID().map(d -> recordReference(IdType.PMCID,d)).ifPresent(tmp::add);
		// getPMID().map(d -> recordReference(IdType.PMID,d)).ifPresent(tmp::add);
		return tmp;
	}

	@Override
	public Stream<String> getLicenses() {
		try {
			return this.raw.doXpath(".//license")
				.getManyAsStream(XmlElement.class)
				.map(
					el -> el.getTextContent()
						.orElse("")
				);
		} catch (XmlException e) {
			return Stream.empty();
		}
	}

	@Override
	public Optional<String> getJournal() {
		try {
			return this.raw.doXpath(".//journal-title")
				.get(XmlElement.class)
				.flatMap(XmlElement::getTextContent);
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public List<JatsReference> getReferences() {
		try {
			return this.raw.doXpath(".//ref-list/ref/citation")
				.getManyAsStream(XmlElement.class)
				.map(p -> new JatsReference(p, this.prov))
				.collect(Collectors.toList());
		} catch (XmlException e) {
			return Collections.emptyList();
		}
	}

	@Override
	public List<CitationLink> getCitations() {
		List<CitationLink> tmp = new ArrayList<>();
		Integer i = 1;
		for (JatsReference r : (Iterable<JatsReference>) this
			.getReferences()::iterator) {
			tmp.add(
				ImmutableCitationLink.builder()
					.index(i)
					.source(Builder.citationReference(this))
					.target(Builder.citationReference(r))
					.build()
			);
			i += 1;
		}
		return tmp;
	}

	@Override
	public Optional<Long> getCitedByCount() { return Optional.empty(); }

	@Override
	public Optional<Long> getReferencesCount() {
		return Optional.of(
			(long) this.getReferences()
				.size()
		);
	}

	@Override
	public Optional<URI> getPdfUri() { return Optional.empty(); }

}