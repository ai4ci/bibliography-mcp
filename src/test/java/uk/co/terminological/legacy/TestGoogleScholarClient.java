package uk.co.terminological.legacy;



import java.io.IOException;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;


import uk.co.terminological.bibliography.BibliographicApiException;
import uk.co.terminological.bibliography.scholar.GoogleResult;
import uk.co.terminological.bibliography.scholar.GoogleScholarClient;

@Deprecated
public class TestGoogleScholarClient {

	static String[] articles =  {
		"Collier,N., Nobata,C. and Tsujii,J. (2000) Extracting the names of genes and gene products with a hidden Markov model. In Proceedings of the 18th International Conference on Computational Lingustics (COLING'2003), Saarbrucken, Germany, pp. 201-207.",
		"Kulick,S., Liberman,M., Palmer,M. and Schein,A. (2003) Shallow semantic annotations of biomedical corpora for information extraction. In Proceedings of the Third Meeting of the Special Interest Group on Text Mining at ISMB 2003.",
		"Lafferty,J., McCallum,A. and Pereira,F. (2001) Conditional Random Fields: probabilistic models for segmenting and labeling sequence data. In Proceedings of ICML-01, pp. 282-289.",
		"41. DAWSON, A. E., AUSTIN, R. E. and WENBER, D. S., American Journal of Clinical Pathology, 95 (1991), 29."
	};
	
	
	static String[] dois = {
			"10.3115/990820.990850",
			null,
			null
	};
	
	static String DEVELOPER = "test@example.org";
	
	public static void main(String[] args) throws BibliographicApiException, IOException {
		
		BasicConfigurator.configure();
		Logger.getRootLogger().setLevel(Level.ALL);
		//Path tmp = Files.createTempDirectory("test");
		
		GoogleScholarClient xref = GoogleScholarClient.create();
		xref.debugMode();
		
		GoogleResult res = xref.findByString(articles[0]).orElseThrow(() -> new RuntimeException());
				
		System.out.println(res.raw());
		
			
		
	}

}
