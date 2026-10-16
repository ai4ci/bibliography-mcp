package uk.co.terminological.legacy;

import java.io.IOException;
import java.util.Optional;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import uk.co.terminological.bibliography.api.BibliographicApiException;
import uk.co.terminological.bibliography.api.crossref.CrossRefClient;
import uk.co.terminological.bibliography.api.crossref.CrossRefWork;
import uk.co.terminological.bibliography.api.crossref.CrossRefClient.Field;
import uk.co.terminological.bibliography.api.crossref.CrossRefClient.QueryBuilder;
import uk.co.terminological.bibliography.api.crossref.CrossRefClient.Sort;
import uk.co.terminological.bibliography.api.crossref.CrossRefClient.SortOrder;

public class TestCrossRefClient {

	static String[] articles = {
			"Collier,N., Nobata,C. and Tsujii,J. (2000) Extracting the names of genes and gene products with a hidden Markov model. In Proceedings of the 18th International Conference on Computational Lingustics (COLING'2003), Saarbrucken, Germany, pp. 201-207.",
			"Kulick,S., Liberman,M., Palmer,M. and Schein,A. (2003) Shallow semantic annotations of biomedical corpora for information extraction. In Proceedings of the Third Meeting of the Special Interest Group on Text Mining at ISMB 2003.",
			"Lafferty,J., McCallum,A. and Pereira,F. (2001) Conditional Random Fields: probabilistic models for segmenting and labeling sequence data. In Proceedings of ICML-01, pp. 282-289.",
			"41. DAWSON, A. E., AUSTIN, R. E. and WENBER, D. S., American Journal of Clinical Pathology, 95 (1991), 29." };

	static String[] dois = { "10.3115/990820.990850", null, null };

	static String DEVELOPER = "test@example.org";

	public static void main(String[] args)
			throws BibliographicApiException, IOException {

		BasicConfigurator.configure();
		Logger.getRootLogger()
			.setLevel(Level.ALL);
		// Path tmp = Files.createTempDirectory("test");

		CrossRefClient xref = CrossRefClient.create(DEVELOPER);
		xref.debugMode();

		QueryBuilder qb = xref.buildQuery()
			.withSearchTerm(Field.BIBLIOGRAPHIC, articles[0])
			.sortedBy(Sort.SCORE, SortOrder.DESC)
			.limit(1);

		for (int i = 0; i < 5; i++) {
			qb.execute();

			for (String ref : articles) {
				System.out.println(ref);
				Optional<CrossRefWork> work = xref.findWorkByCitationString(ref);
				work.ifPresent(w -> {
					System.out.println(w.getTitle());
					w.getCitedByCount()
						.ifPresent(System.out::println);
					w.getIdentifier()
						.ifPresent(System.out::println);
					w.getJournal()
						.ifPresent(System.out::println);
				});
				System.out.println();
			}

		}
	}

}
