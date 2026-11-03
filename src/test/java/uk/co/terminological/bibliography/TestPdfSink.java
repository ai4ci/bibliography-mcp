package uk.co.terminological.bibliography;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;

/**
 * plan-012 item C: a PDF that exists as a file.
 *
 * {@code getPdfFromUrl} hands back an {@code InputStream} over a cache blob and writes nothing, which blocks two
 * consumers at once: Zotero cannot be given an attachment it cannot read from disk, and {@code prag} has no directory
 * to index. The sink is the ~30 lines and a path convention that unblock both.
 *
 * These assert the sink and the naming, not the downloading: what arrives is a stream either way.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestPdfSink {

	private static final byte[] PDF = "%PDF-1.4\nnot really a pdf\n%%EOF"
		.getBytes();

	private Path root;

	@Before
	public void makeRoot() throws Exception {
		this.root = Files.createTempDirectory("biblio-pdf-test");
	}

	@Test
	public void aStreamBecomesAFileOnDisk() throws Exception {
		var destination = this.root.resolve("one.pdf");

		var written = PdfSink.write(new ByteArrayInputStream(PDF), destination);

		assertEquals(destination, written);
		assertTrue(Files.exists(destination));
		assertArrayEquals(PDF, Files.readAllBytes(destination));
	}

	@Test
	public void theDirectoryIsCreatedRatherThanRequired() throws Exception {
		var destination = this.root.resolve("a")
			.resolve("b")
			.resolve("deep.pdf");

		PdfSink.write(new ByteArrayInputStream(PDF), destination);

		assertTrue(Files.exists(destination));
	}

	@Test
	public void aRecordGetsAPathNamedAfterItsIdentifier() {
		var doi = Builder.recordReference(IdType.DOI, "10.1136/bmj.n579");

		var path = PdfSink.pathFor(this.root, doi);

		// the identifier is the name, with the characters a filesystem cannot take
		// replaced — a DOI has a slash in it, and a crawl must not be able to write
		// outside the directory it was given by naming a record cleverly
		assertEquals(this.root, path.getParent());
		assertFalse(
			path.getFileName()
				.toString(),
			path.getFileName()
				.toString()
				.contains("/")
		);
		assertTrue(
			path.getFileName()
				.toString(),
			path.getFileName()
				.toString()
				.endsWith(".pdf")
		);
		assertTrue(
			path.getFileName()
				.toString(),
			path.getFileName()
				.toString()
				.contains("10.1136")
		);
	}

	@Test
	public void twoRecordsDoNotShareAPath() {
		var one = PdfSink.pathFor(
			this.root, Builder.recordReference(IdType.DOI, "10.1/a")
		);
		var two = PdfSink.pathFor(
			this.root, Builder.recordReference(IdType.DOI, "10.1/b")
		);

		assertFalse(one.equals(two));
	}

	@Test
	public void anIdentifierCannotEscapeTheDirectoryItIsWrittenIn() {
		// a DOI is publisher-controlled text; nothing in it may become a path segment
		var nasty = Builder
			.recordReference(IdType.DOI, "../../../etc/passwd");

		var path = PdfSink.pathFor(this.root, nasty);

		assertEquals(this.root, path.getParent());
		assertTrue(
			path.normalize()
				.startsWith(this.root)
		);
	}

	@Test
	public void anEmptyDownloadIsRefusedRatherThanWrittenAsAnEmptyFile()
			throws Exception {
		var destination = this.root.resolve("empty.pdf");

		try {
			PdfSink.write(new ByteArrayInputStream(new byte[0]), destination);
			fail("an empty pdf should not be written");
		} catch (java.io.IOException e) {
			assertFalse(
				"and nothing should be left behind", Files.exists(destination)
			);
		}
	}

	@Test
	public void whatIsNotAPdfIsRefusedBeforeItReachesTheLibrary()
			throws Exception {
		// a publisher that answers a pdf url with an html paywall page is the usual
		// way this fails, and Zotero should never be handed the result
		var destination = this.root.resolve("paywall.pdf");

		try {
			PdfSink.write(
				new ByteArrayInputStream(
						"<html><body>Please log in</body></html>".getBytes()
				), destination
			);
			fail("html should not be written as a pdf");
		} catch (java.io.IOException e) {
			assertTrue(
				e.getMessage(), e.getMessage()
					.contains("not a pdf")
			);
			assertFalse(Files.exists(destination));
		}
	}

	@Test
	public void theMd5OfWhatWasWrittenIsReportedForZotero() throws Exception {
		var destination = this.root.resolve("hashed.pdf");
		PdfSink.write(new ByteArrayInputStream(PDF), destination);

		var md5 = PdfSink.md5(destination);

		// Zotero registers an uploaded file by its md5; computing it here means the
		// bytes are hashed once, from the file that will be attached
		assertEquals(32, md5.length());
		assertEquals(md5, PdfSink.md5(destination));
		assertEquals(Optional.of(md5), Optional.of(md5.toLowerCase()));
	}

}
