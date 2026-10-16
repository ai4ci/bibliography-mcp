package uk.co.terminological.bibliography;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.apache.commons.io.IOUtils;

import uk.co.terminological.bibliography.api.BibliographicApiException;

public class BinaryData implements Serializable {
	byte[] byteArray;
	private BinaryData(byte[] bytes) {
		byteArray = bytes;
	}

	public static BinaryData from(byte[] st) {
		return new BinaryData(st);
	}
	public static BinaryData from(String st) {
		return new BinaryData(st.getBytes());
	}
	public static BinaryData from(InputStream is)
			throws BibliographicApiException {
		try {
			return new BinaryData(IOUtils.toByteArray(is));
		} catch (IOException e) {
			throw new BibliographicApiException("Could not read api response", e);
		}
	}
	public static BinaryData from(Serializable ser)
			throws BibliographicApiException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos;
		try {
			oos = new ObjectOutputStream(baos);
			oos.writeObject(ser);
			oos.flush();
		} catch (IOException e) {
			throw new BibliographicApiException("could not serialize object", e);
		}

		return new BinaryData(baos.toByteArray());
	}
	public InputStream inputStream() {
		return new ByteArrayInputStream(byteArray);
	}
	public String toString() {
		return new String(byteArray);
	}
	@SuppressWarnings("unchecked")
	public <X extends Serializable> X toObject()
			throws BibliographicApiException {
		try {
			ObjectInputStream ois = new ObjectInputStream(this.inputStream());
			return (X) ois.readObject();
		} catch (ClassNotFoundException | IOException e) {
			throw new BibliographicApiException("could not deserialize object", e);
		}
	}
}