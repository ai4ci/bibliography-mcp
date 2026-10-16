package uk.co.terminological.bibliography;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.record.Raw;

public class ExtensibleJson implements Raw<JsonNode> {

	static final Logger logger = LoggerFactory.getLogger(ExtensibleJson.class);

	private JsonNode raw;
	protected void setRaw(JsonNode raw) { this.raw = raw; }
	public JsonNode getRaw() { return raw; }

	public ExtensibleJson() {}

	public ExtensibleJson(JsonNode node) {
		this.raw = node;
	}

	public Stream<ExtensibleJson> streamNode() {
		if (raw.isArray())
			return StreamSupport
				.stream(
					Spliterators
						.spliteratorUnknownSize(raw.elements(), Spliterator.ORDERED),
					false
				)
				.map(s -> new ExtensibleJson(s));
		else
			return Stream.of(new ExtensibleJson(raw));
	}

	public <X extends ExtensibleJson> Stream<X> streamNode(Class<X> subtype) {
		if (raw.isArray())
			return StreamSupport
				.stream(
					Spliterators
						.spliteratorUnknownSize(raw.elements(), Spliterator.ORDERED),
					false
				)
				.map(s -> {
					try {
						return subtype.getDeclaredConstructor(JsonNode.class)
							.newInstance(s);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});
		else
			try {
				return Stream.of(
					subtype.getDeclaredConstructor(JsonNode.class)
						.newInstance(raw)
				);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
	}

	public Stream<ExtensibleJson> streamNode(String key) {
		JsonNode node = raw.get(key);
		if (node == null || node.isNull() || node.isMissingNode())
			return Stream.empty();
		if (node.isArray())
			return StreamSupport
				.stream(
					Spliterators
						.spliteratorUnknownSize(node.elements(), Spliterator.ORDERED),
					false
				)
				.map(s -> new ExtensibleJson(s));
		else
			return Stream.of(new ExtensibleJson(node));
	}

	public Stream<ExtensibleJson> streamPath(String... keys) {
		Stream<ExtensibleJson> out = Stream.of(this);
		for (String key : keys) {
			out = out.flatMap(t -> t.streamNode(key));
		}
		return out;
	}

	public <X extends ExtensibleJson> Stream<X> streamNode(
			Class<X> subtype, String key
	) {
		JsonNode node = raw.get(key);
		if (node == null || node.isNull() || node.isMissingNode())
			return Stream.empty();
		if (node.isArray())
			return StreamSupport
				.stream(
					Spliterators
						.spliteratorUnknownSize(node.elements(), Spliterator.ORDERED),
					false
				)
				.map(s -> {
					try {
						return subtype.getDeclaredConstructor(JsonNode.class)
							.newInstance(s);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});
		else
			try {
				return Stream.of(
					subtype.getDeclaredConstructor(JsonNode.class)
						.newInstance(node)
				);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
	}

	public <X extends ExtensibleJson> Stream<X> streamNode(
			Function<ExtensibleJson, X> converter, String key
	) {
		JsonNode node = raw.get(key);
		if (node == null || node.isNull() || node.isMissingNode())
			return Stream.empty();
		if (node.isArray())
			return StreamSupport
				.stream(
					Spliterators
						.spliteratorUnknownSize(node.elements(), Spliterator.ORDERED),
					false
				)
				.map(s -> converter.apply(new ExtensibleJson(s)));
		else
			try {
				return Stream.of(converter.apply(new ExtensibleJson(node)));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
	}

	public <X extends ExtensibleJson> Stream<X> streamPath(
			Class<X> subtype, String... keys
	) {
		Stream<ExtensibleJson> out = Stream.of(this);
		Iterator<String> keysIt = Arrays.asList(keys)
			.iterator();
		while (keysIt.hasNext()) {
			String key = keysIt.next();
			if (!keysIt.hasNext()) {
				return out.flatMap(t -> t.streamNode(subtype, key));
			} else {
				out = out.flatMap(t -> t.streamNode(key));
			}
		}
		return Stream.empty();
	}

	public <X extends ExtensibleJson> Stream<X> streamPath(
			Function<ExtensibleJson, X> converter, String... keys
	) {
		Stream<ExtensibleJson> out = Stream.of(this);
		Iterator<String> keysIt = Arrays.asList(keys)
			.iterator();
		while (keysIt.hasNext()) {
			String key = keysIt.next();
			if (!keysIt.hasNext()) {
				return out.flatMap(t -> t.streamNode(converter, key));
			} else {
				out = out.flatMap(t -> t.streamNode(key));
			}
		}
		return Stream.empty();
	}

	public String asString() {
		return raw.asText();
	}

	public Double asDouble() {
		return raw.asDouble();
	}

	public Long asLong() {
		return raw.asLong();
	}

	public Optional<String> asString(String key) {
		JsonNode tmp = raw.get(key);
		if (tmp == null) return Optional.empty();
		return Optional.ofNullable(tmp.asText(null));
	}

	public Optional<Double> asDouble(String key) {
		if (!raw.has(key)) return Optional.empty();
		JsonNode tmp = raw.get(key);
		if (!tmp.isDouble()) return Optional.empty();
		return Optional.of(
			raw.get(key)
				.asDouble()
		);
	}

	public Optional<Long> asLong(String key) {
		if (!raw.has(key)) return Optional.empty();
		JsonNode tmp = raw.get(key);
		if (!tmp.canConvertToLong()) return Optional.empty();
		return Optional.of(
			raw.get(key)
				.asLong()
		);
	}

	public <X extends ExtensibleJson> Optional<X> asObject(
			Class<X> subtype, String key
	) {
		if (!raw.has(key)) return Optional.empty();
		JsonNode tmp = raw.get(key);
		try {
			return Optional.of(
				subtype.getDeclaredConstructor(JsonNode.class)
					.newInstance(tmp)
			);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/*
	 * Map<String,Object> unknownProperties = new HashMap<>();
	 * 
	 * @JsonAnySetter public void handleUnknownProperty(String key, Object value) {
	 * logger.debug(getClass().getCanonicalName()+" - unknown property: {}: {}", key, value.toString());
	 * unknownProperties.put(key, value); }
	 * 
	 * public Object getUnknownProperty(String key) { return unknownProperties.get(key); }
	 */
}