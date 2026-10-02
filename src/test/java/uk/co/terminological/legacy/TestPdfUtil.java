package uk.co.terminological.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.log4j.BasicConfigurator;

import uk.co.terminological.bibliography.BibliographicApiException;
import uk.co.terminological.bibliography.PdfFetcher;

public class TestPdfUtil {

	public static String[] urls = {
			"https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6227809/pdf/rsfs20180033.pdf",
			"https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6227809/pdf",
			"http://arxiv.org/pdf/1811.08430",
			"https://doi.org/10.1016/j.scitotenv.2018.09.257"
	};
	
	public static void main(String[] args) throws IOException, BibliographicApiException {

		BasicConfigurator.configure();
		Path tmp = Files.createTempDirectory("pdf_test");
		System.out.println(tmp.toString());
		
		for (int i=0; i<5; i++) {
			int k = 0;
			for (String url: urls) {
				Files.copy(
						PdfFetcher.create(tmp).getPdfFromUrl(url).get(),
						tmp.resolve(i+"_"+k+".pdf"));
				k++;
			}
		}
		
	}

}
