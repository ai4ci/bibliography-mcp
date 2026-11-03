package uk.co.terminological.bibliography;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import uk.co.terminological.bibliography.record.RecordReference;

/**
 * Where a downloaded PDF becomes a file.
 *
 * {@code PdfFetcher.getPdfFromUrl} answers with a stream over a cache blob and writes nothing, which blocks two
 * consumers on the same thirty lines: Zotero cannot be given an attachment it cannot read from disk, and anything that
 * indexes PDF content needs a plain directory to point at. This is that directory convention, and nothing more — the
 * sink deliberately knows about paths and bytes, not about Zotero.
 *
 * <h2>What it refuses</h2>
 *
 * An empty body, and a body that is not a PDF. A publisher answering a PDF url with an HTML paywall page is the
 * ordinary way an open-access link fails, and the failure has to stop here: an HTML file with a {@code .pdf} name in
 * someone's library is worse than no attachment, because it looks like a success.
 */
// [IMPLEMENTS](/design/features/feat-010-crawl-engine-with-zotero-mirror.md)
public class PdfSink {

	private PdfSink() {}

	/** The longest a generated filename may be, leaving room for the extension on every filesystem that matters. */
	private static final int MAX_NAME = 180;

	/**
	 * Write a stream to a file, atomically enough that a reader never sees half a PDF.
	 *
	 * Written beside the destination and moved into place, so a crash leaves either the whole file or nothing — a crawl
	 * that dies mid-download must not leave a truncated attachment that looks complete.
	 *
	 * @return the path written
	 * @throws IOException if the body is empty or is not a PDF; nothing is left behind either way
	 */
	public static Path write(InputStream from, Path destination)
			throws IOException {
		var parent = destination.toAbsolutePath()
			.getParent();
		if (parent != null) Files.createDirectories(parent);
		var partial = Files.createTempFile(parent, ".pdf-", ".part");
		long written;
		try (OutputStream out = Files.newOutputStream(partial)) {
			written = from.transferTo(out);
		} catch (IOException e) {
			Files.deleteIfExists(partial);
			throw e;
		}
		try {
			if (written == 0) throw new IOException(
					"nothing was downloaded for " + destination.getFileName()
			);
			if (!looksLikePdf(partial)) throw new IOException(
					"what was downloaded is not a pdf (a paywall page, most likely): "
							+ destination.getFileName()
			);
			Files.move(
				partial, destination, StandardCopyOption.REPLACE_EXISTING
			);
		} catch (IOException e) {
			Files.deleteIfExists(partial);
			throw e;
		}
		return destination;
	}

	/** A PDF says so in its first five bytes, and an HTML error page does not. */
	private static boolean looksLikePdf(Path file) throws IOException {
		byte[] head = new byte[5];
		try (InputStream in = Files.newInputStream(file)) {
			int read = in.readNBytes(head, 0, 5);
			if (read < 5) return false;
		}
		return new String(head).equals("%PDF-");
	}

	/**
	 * Where a record's PDF belongs under a directory.
	 *
	 * Named after the identifier, because that is what makes the directory legible to a human and joinable by anything
	 * else that has the record. Every character a filesystem might read as structure is replaced: a DOI contains a
	 * slash, identifiers come from publishers rather than from us, and a file must land in the directory it was given
	 * however the identifier is spelt.
	 */
	public static Path pathFor(Path directory, RecordReference ref) {
		var id = ref.getIdentifier()
			.orElse("unidentified");
		var safe = id.replaceAll("[^A-Za-z0-9._-]", "_");
		// a name of dots is still a path, and "..".pdf is not a filename worth risking
		safe = safe.replaceAll("^[.]+", "_");
		if (safe.length() > MAX_NAME) safe = safe.substring(0, MAX_NAME);
		return directory.resolve(
			ref.getIdentifierType()
				.name()
				.toLowerCase() + "-" + safe + ".pdf"
		);
	}

	/**
	 * The md5 of a file, lower case hex.
	 *
	 * Zotero registers an uploaded file by its md5, so the bytes are hashed once, from the file that will be attached,
	 * rather than from whatever was in memory when it was downloaded.
	 */
	public static String md5(Path file) throws IOException {
		try {
			var digest = MessageDigest.getInstance("MD5");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = in.read(buffer)) > 0)
					digest.update(buffer, 0, read);
			}
			var out = new StringBuilder();
			for (byte b : digest.digest())
				out.append(String.format("%02x", b));
			return out.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("this jvm has no md5", e);
		}
	}

}
