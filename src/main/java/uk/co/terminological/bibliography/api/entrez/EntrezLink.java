package uk.co.terminological.bibliography.api.entrez;

import java.util.Optional;

/**
 * 
 * type: https://eutils.ncbi.nlm.nih.gov/entrez/query/static/entrezlinks.html
 * 
 * pmc_pmc_cites Articles that Cites other Articles that Cites other PMC articles that given PMC article cites 3000000
 * pmc_pmc_citedby Cited Articles Cited Articles PMC article citing given PMC article 3000000 pmc_pubmed PubMed PubMed
 * Links PubMed citations for these articles 10000 pubmed_pmc - PMC Links Free full-text versions of the current
 * articles in the PubMed Central database. 10000 pubmed_pmc_local Free in PMC - Free full text articles in PMC 10000
 * pubmed_pmc_refs Cited in PMC Cited in PMC Full-text articles in the PubMed Central Database that cite the current
 * articles. 10000 pubmed_pubmed Similar articles Similar articles Calculated set of PubMed citations similar to the
 * selected article(s) retrieved using a word weight algorithm. Similar articles are displayed in ranked order from most
 * to least similar, with the linked from citation displayed first. 500 pubmed_pubmed_refs References for this PMC
 * Article References for PMC Articles Citation referenced in PubMed article. Only valid for PubMed citations that are
 * also in PMC.
 * 
 * @author terminological
 *
 */
public class EntrezLink {
	public String fromDb;
	public String fromId;
	public Optional<String> typeOrCategory = Optional.empty();
	public Optional<Long> score = Optional.empty();
	public String toDbOrUrl;
	public Optional<String> toId = Optional.empty();

	protected EntrezLink(
			String dbFrom, String fromId, Optional<String> category, String toUrl
	) {
		this.fromDb = dbFrom;
		this.fromId = fromId;
		this.typeOrCategory = category;
		this.toDbOrUrl = toUrl;
	}

	protected EntrezLink(
			String fromDb, String fromId, Optional<String> type,
			Optional<String> toDb, String toId, Optional<Long> score
	) {
		this.fromDb = fromDb;
		this.fromId = fromId;
		this.typeOrCategory = type;
		this.toDbOrUrl = toDb.orElse(fromDb);
		this.toId = Optional.of(toId);
		this.score = score;
	}

	@Override
	public String toString() {
		return this.fromDb + "\t" + this.fromId + "\t"
				+ this.typeOrCategory.orElse("") + "\t" + this.toDbOrUrl + "\t"
				+ this.toId.orElse("") + "\t" + this.score.orElse(0L);
	}

}