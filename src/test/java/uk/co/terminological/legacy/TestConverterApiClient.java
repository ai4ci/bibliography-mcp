package uk.co.terminological.legacy;

import java.util.Arrays;

import org.apache.log4j.BasicConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.terminological.bibliography.BibliographicApiException;
import uk.co.terminological.bibliography.pmcidconv.PMCIDClient;
import uk.co.terminological.bibliography.record.IdType;

public class TestConverterApiClient {

	
	public static Logger logger = LoggerFactory.getLogger(TestPubMedRestClient.class);
	public static String APP_ID = "test_client";
	public static String DEVELOPER_EMAIL = "rob@terminological.co.uk";
	
	public static void main(String[] args) throws BibliographicApiException {
		
		BasicConfigurator.configure();
		PMCIDClient client = PMCIDClient.create(APP_ID,DEVELOPER_EMAIL);
		client.debugMode();
		client.getDoisByIdAndType(Arrays.asList("12964947"), IdType.PMID).forEach((k,v) -> System.out.println(k+"->"+v));
		client.getDoisByIdAndType(Arrays.asList("12964947"), IdType.PMID).forEach((k,v) -> System.out.println(k+"->"+v));
		client.getDoisByIdAndType(Arrays.asList("12964947"), IdType.PMID).forEach((k,v) -> System.out.println(k+"->"+v));
		client.getDoisByIdAndType(Arrays.asList("12964947"), IdType.PMID).forEach((k,v) -> System.out.println(k+"->"+v));
	}

}
