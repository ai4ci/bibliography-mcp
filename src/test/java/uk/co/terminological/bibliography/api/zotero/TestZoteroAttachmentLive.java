package uk.co.terminological.bibliography.api.zotero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.PdfSink;

/**
 * The production attachment path, against the live library.
 *
 * prototype-003 proved the protocol with raw HTTP; this proves the code that implements it — the same four calls
 * through {@code ZoteroClient}, with the confinement guard in place.
 *
 * <h2>It writes, and it asks</h2>
 *
 * Everything lands in a sub-collection of {@code api-test} ({@value #TEST_COLLECTION}), which is the sanctioned
 * target, and the first write of a run raises a dialog in the Zotero window that waits two minutes for a human. That
 * is why this is in the live tier and will never run in CI.
 *
 * The configuration is built here rather than read from the properties file on purpose: enabling writes globally is
 * a decision for whoever owns the library, not a side effect of running a test.
 *
 * [TESTS](/design/test-scripts/test-003-zotero-bridge-tests.md)
 */
@Category(LiveTest.class)
public class TestZoteroAttachmentLive {

	/** api-test: empty, sanctioned, and the only place these tests may write */
	static final String TEST_COLLECTION = "TB35FFX6";

	private static ZoteroClient writableClient() {
		var properties = new Properties();
		properties.setProperty("zotero-mode", "local");
		properties.setProperty("zotero-allow-writes", "true");
		properties.setProperty("zotero-write-collection", TEST_COLLECTION);
		return new ZoteroClient(ZoteroConfig.from(properties));
	}

	private static Path aPdf() throws Exception {
		var file = Files.createTempFile("live-attach-", ".pdf");
		// a small valid pdf: the point is the protocol, not the payload
		Files.write(
			file,
			("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF")
				.getBytes()
		);
		return file;
	}

	@Test
	public void aRecordAndItsPdfReachTheLibrary() throws Exception {
		var zotero = writableClient();
		zotero.checkAvailable();
		var file = aPdf();
		var expectedMd5 = PdfSink.md5(file);

		// the shape zotero_sync uses: a sub-collection of the write target, items in
		// it, then an attachment on each
		var collection = zotero.createSubCollection(
			"api-test attachment run " + System.currentTimeMillis()
		);
		assertNotNull(collection);

		var item = ZoteroItemWriter.toItem(
			new uk.co.terminological.bibliography.repository.RecordFixture(
					"10.0000/live-attach-" + System.currentTimeMillis(),
					"Attachment path, end to end"
			), collection, List.of("prototype-003")
		);
		var written = zotero.createItems(List.of(item), collection);
		assertTrue(
			"nothing was created: " + written.failures,
			written.createdByIndex.containsKey(0)
		);
		var itemKey = written.createdByIndex.get(0);

		var attachmentKey = zotero.attachPdf(itemKey, collection, file);

		assertNotNull("no attachment key came back", attachmentKey);
		var attachment = zotero.item(attachmentKey)
			.orElse(null);
		assertNotNull("the attachment was not readable afterwards", attachment);
		System.out.println(
			"INFO: created " + itemKey + " with attachment " + attachmentKey
					+ " in collection " + collection
		);

		// Zotero's own record of what it holds is the proof it took the bytes
		var raw = attachment.getData()
			.toString();
		assertTrue(
			"Zotero did not record our md5: " + raw, raw.contains(expectedMd5)
		);
		assertEquals(
			"the attachment should hang off the item we created", itemKey,
			attachment.getData()
				.path("parentItem")
				.asText()
		);
	}

	@Test
	public void anAttachmentOutsideTheWriteCollectionIsRefused()
			throws Exception {
		var zotero = writableClient();
		zotero.checkAvailable();

		try {
			// a collection key that is not the write target and not inside it
			zotero.attachPdf("SOMEITEM", "ZZZZZZZZ", aPdf());
			org.junit.Assert.fail("a write outside the target should be refused");
		} catch (ZoteroClient.ZoteroException e) {
			assertTrue(
				e.getMessage(), e.getMessage()
					.contains("confined")
			);
		}
	}

}
