package uk.co.terminological.bibliography;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;

import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * One real open-access PDF, onto the disk.
 *
 * The offline tests assert the sink; this asserts that what the publishers actually serve goes through it — a real
 * body, a real content type, and a file that is a PDF at the end of it.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
@Category(LiveTest.class)
public class TestPdfSinkLive {

	@Test
	public void anOpenAccessPdfBecomesAFile() throws Exception {
		var dir = Files.createTempDirectory("biblio-pdf-live");
		var fetcher = PdfFetcher
			.create(java.nio.file.Paths.get("/tmp/ehcache/pdf-live-test"));

		// arXiv, because it is one of the few open-access hosts that actually serves a
		// pdf to a programmatic client. Measured the same day: BMJ answers its own
		// `.full.pdf` with 403 and an html body, and PMC's /pdf endpoint refuses
		// too. An open-access link is not the same as a fetchable one, which is the
		// thing workflow 2 has to survive.
		var written = fetcher.fetchTo(
			"https://arxiv.org/pdf/2103.00300", dir.resolve("fixture.pdf")
		);

		System.out.println(
			"INFO: wrote " + written.map(p -> {
				try {
					return p + " (" + Files.size(p) + " bytes, md5 "
							+ PdfSink.md5(p) + ")";
				} catch (Exception e) {
					return p.toString();
				}
			})
				.orElse("(nothing)")
		);
		assertTrue("no pdf was written", written.isPresent());
		assertTrue(Files.size(written.get()) > 10_000);
		assertTrue(
			"what was written is not a pdf",
			new String(
					Files.readAllBytes(written.get()), 0, 5
			).equals("%PDF-")
		);
	}

}
