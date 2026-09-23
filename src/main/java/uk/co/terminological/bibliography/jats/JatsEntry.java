package uk.co.terminological.bibliography.jats;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableCitationLink;
import uk.co.terminological.bibliography.record.ImmutableCitationReference;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordWithCitations;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;
import uk.co.terminological.fluentxml.XmlList;

public class JatsEntry implements RecordWithCitations, Raw<XmlElement> {

	private XmlElement raw; //PubmedArticle

	public JatsEntry(XmlElement raw) {this.raw = raw;}

	public XmlElement getRaw() {return raw;}

	public Optional<String> getTitle() {
		try {
			// scoped to the front matter: a JATS reference list also contains
			// <article-title>, so an unscoped match finds the wrong thing or several
			return raw.doXpath(".//front//article-title").getManyAsStream(XmlElement.class)
					.flatMap(el -> el.getTextContent().stream())
					.findFirst();
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Optional<String> getDoi() {
		try {
			return raw.doXpath(".//article-id[@pub-id-type='doi']").get(XmlElement.class).flatMap(o -> o.getTextContent());
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
	 * @return
	 */
	//public abstract Optional<URI> getPdfUri();

	public Optional<String> getPMID() {
		return Optional.empty();
	}
	
	public List<String> getKeyWords() {
		try {
			XmlList<XmlElement> keywords = raw.doXpath(".//article-categories//subject").getMany(XmlElement.class);
			return keywords.stream().flatMap(xmlt -> xmlt.getTextContent().stream()).collect(Collectors.toList());
		} catch (XmlException e) {
			return Collections.emptyList();
		}
	}
	
	public Optional<LocalDate> getDate() {
		try {
			Optional<XmlElement> date = raw.doXpath(".//pub-date[@pub-type='epub']").get(XmlElement.class);
			return date.flatMap(el -> {
				Optional<Integer> year = el.childElements("year").findFirst().flatMap(el2 -> el2.getTextContent()).map(Integer::parseInt);
				Optional<Integer> month = el.childElements("month").findFirst().flatMap(el2 -> el2.getTextContent()).map(Integer::parseInt);
				Optional<Integer> day = el.childElements("day").findFirst().flatMap(el2 -> el2.getTextContent()).map(Integer::parseInt);
				return year.map(y -> LocalDate.of(y, month.orElse(1), day.orElse(1)));
			});
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Optional<String> getAbstract() {return getAbstract(true);}
	public Optional<String> getUnlabelledAbstract() {return getAbstract(false);}

	private Optional<String> getAbstract(final boolean labelled) {
		try {
			String tmp = raw.doXpath(".//front//abstract").getManyAsStream(XmlElement.class)
					.map(el -> el.getTextContent().orElse(""))
					.collect(Collectors.joining("\n"));
			return tmp.isEmpty() ? Optional.empty(): Optional.of(tmp);
		} catch (XmlException e) {
			throw new RuntimeException(e);
		}
	}

	public List<JatsAuthor> getAuthors() {
		try {
			return raw.doXpath(".//contrib[@contrib-type='author']").getManyAsStream(XmlElement.class).map(o -> new JatsAuthor(o)).collect(Collectors.toList());
		} catch (XmlException e) {
			return Collections.emptyList();
		}
	}

	@Override
	public Optional<String> getIdentifier() {
		return getDoi();
	}

	@Override
	public IdType getIdentifierType() {
		return IdType.DOI;
	}

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
			return raw.doXpath(".//license").getManyAsStream(XmlElement.class)
					.map(el -> el.getTextContent().orElse(""));
		} catch (XmlException e) {
			return Stream.empty();
		}
	}

	@Override
	public Optional<String> getJournal() {
		try {
			return raw.doXpath(".//journal-title").get(XmlElement.class).flatMap(o -> o.getTextContent());
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public List<JatsReference> getReferences() {
		try {
			return raw.doXpath(".//ref-list/ref/citation").getManyAsStream(XmlElement.class).map(o -> new JatsReference(o)).collect(Collectors.toList());
		} catch (XmlException e) {
			return Collections.emptyList();
		}
	}
	
	@Override
	public Stream<CitationLink> getCitations() {
		List<CitationLink> tmp = new ArrayList<>();
		Integer i = 1;
		for (JatsReference r: (Iterable<JatsReference>) this.getReferences()::iterator) {
			tmp.add(
				new ImmutableCitationLink(
						new ImmutableCitationReference(this),
						new ImmutableCitationReference(r),
						Optional.of(i)
					));
			i += 1;
		}
		return tmp.stream();
	}

	@Override
	public Optional<Long> getCitedByCount() {
		return Optional.empty();
	}

	@Override
	public Optional<Long> getReferencesCount() {
		return Optional.of((long) getReferences().size());
	}

	@Override
	public Optional<URI> getPdfUri() {
		return Optional.empty();
	}

	

	

	
}