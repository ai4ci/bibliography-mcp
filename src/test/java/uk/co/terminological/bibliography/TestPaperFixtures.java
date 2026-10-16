package uk.co.terminological.bibliography;

/**
 * Expected paper details hardcoded from the local Zotero library (item key {@code J2S7DC97}), used across end-to-end
 * client retrieval and parsing tests.
 * <p>
 * The paper was originally deposited as a medRxiv preprint with DOI {@value #PREPRINT_DOI} and subsequently
 * peer-reviewed and published in BMC Infectious Diseases with DOI {@value #PUBLISHED_DOI} (PubMed identifier
 * {@value #PMID}, Europe PMC identifier {@value #PMID} under source {@code MED}, PMCID {@value #PMCID}).
 */
public final class TestPaperFixtures {

	private TestPaperFixtures() {}

	/** The preprint DOI as stored in Zotero (item J2S7DC97) */
	public static final String PREPRINT_DOI = "10.64898/2025.12.12.25342129";

	/** The published article DOI in BMC Infectious Diseases */
	public static final String PUBLISHED_DOI = "10.1186/s12879-026-13122-y";

	/** The PubMed identifier assigned to the published paper */
	public static final String PMID = "41888721";

	/** The PubMed Central identifier assigned to the published paper */
	public static final String PMCID = "PMC13141338";

	/**
	 * A citation-rich preprint (EPMC PPR285723, published as BMJ n579) used to exercise the citation/reference parsers
	 * against non-empty data
	 */
	public static final String CITATION_RICH_DOI = "10.1101/2021.02.09.21250937";

	/**
	 * The version of record of the citation-rich preprint: OpenCitations aggregates incoming citations against this DOI,
	 * not the preprint's
	 */
	public static final String CITATION_RICH_VOR_DOI = "10.1136/bmj.n579";

	/**
	 * Expected title from Zotero: "Trends in invasive and non-invasive Streptococcus pneumoniae disease in adults
	 * hospitalised in Bristol and Bath: a retrospective cohort study, 2006-2022"
	 */
	public static final String EXPECTED_TITLE = "Trends in invasive and non-invasive Streptococcus pneumoniae disease in adults hospitalised in Bristol and Bath: a retrospective cohort study, 2006-2022";

	/** Expected journal title */
	public static final String EXPECTED_JOURNAL_TITLE = "BMC infectious diseases";

	/** Expected journal ISO/MEDLINE abbreviation */
	public static final String EXPECTED_JOURNAL_ABBR = "BMC Infect Dis";

	/** Expected repository/server for the preprint (Zotero {@code repository} field; Unpaywall {@code journal_name}) */
	public static final String EXPECTED_REPOSITORY = "medRxiv";

	/** Expected 16 author surnames in author order from Zotero */
	public static final String[] EXPECTED_AUTHOR_SURNAMES = { "Challen", "Hyams",
			"Hettle", "Amin-Chowdhury", "Grimes", "Ruffino", "Conway", "Heath",
			"North", "Malin", "Maskell", "Williams", "Williams", "Ladhani",
			"Danon", "Finn" };

	/** Expected 16 author first/given names in author order from Zotero */
	public static final String[] EXPECTED_AUTHOR_FIRST_NAMES = { "Robert",
			"Catherine", "David", "Zahin", "Charli", "Gabriella", "Rauri", "Robyn",
			"Paul", "Adam", "Nick A", "Philip", "O Martin", "Shamez N", "Leon",
			"Adam" };
}
