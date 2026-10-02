package uk.co.terminological.bibliography.pmcidconv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.PMID;
import static uk.co.terminological.bibliography.TestPaperFixtures.PMCID;
import static uk.co.terminological.bibliography.TestPaperFixtures.PUBLISHED_DOI;

import java.io.InputStream;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

/**
 * Tier-1 extraction tests (plan-007): recorded PMC ID Conversion API responses parsed
 * exactly as PMCIDClient parses them, for all three capture modes: a DOI lookup, a PMID
 * lookup and a clean miss.
 */
public class TestPMCIDExtraction {

	static PMCIDResult byDoi;
	static PMCIDResult byPMID;
	static PMCIDResult miss;

	@BeforeClass
	public static void parseTheRecordedCorpus() throws Exception {
		byDoi = result("fixtures/pmcid/doi.json");
		byPMID = result("fixtures/pmcid/pmid.json");
		miss = result("fixtures/pmcid/miss.json");
	}

	/** the same mapper configuration the client itself uses */
	static PMCIDResult result(String fixture) throws Exception {
		ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());
		try (InputStream is = ClassLoader.getSystemResourceAsStream(fixture)) {
			return mapper.readValue(is, PMCIDResult.class);
		}
	}

	@Test
	public void aDoiLookupMapsTheWholeIdentifierTriple() {
		assertEquals("ok", byDoi.status.orElse("<none>"));
		assertEquals(1, byDoi.records.size());
		PMCIDRecord record = byDoi.records.get(0);
		assertFalse(record.idNotFound());
		assertEquals(PMCID, record.pmcid.orElse("<none>"));
		assertEquals(PMID, record.pmid.orElse("<none>"));
		assertEquals(PUBLISHED_DOI, record.doi.orElse("<none>"));
		assertEquals("the record should map all three identifiers",
			3, record.getMapping().size());
	}

	@Test
	public void aPmidLookupFindsTheSameArticle() {
		PMCIDRecord record = byPMID.records.get(0);
		assertFalse(record.idNotFound());
		assertEquals(PMCID, record.pmcid.orElse("<none>"));
		assertEquals(PMID, record.pmid.orElse("<none>"));
		assertEquals(PUBLISHED_DOI, record.doi.orElse("<none>"));
	}

	@Test
	public void anUnknownDoiIsACleanNotFoundNotAnError() {
		assertEquals("ok", miss.status.orElse("<none>"));
		assertEquals(1, miss.records.size());
		PMCIDRecord record = miss.records.get(0);
		assertTrue("the unknown DOI should be reported as not found", record.idNotFound());
		assertEquals(PUBLISHED_DOI.replace("13122-y", "99999-x"), record.doi.orElse("<none>"));
		assertFalse("no pmcid should be invented for it", record.pmcid.isPresent());
		assertTrue("an explanation should be given",
			record.errmsg.orElse("<none>").toLowerCase().contains("not found"));
	}
}
