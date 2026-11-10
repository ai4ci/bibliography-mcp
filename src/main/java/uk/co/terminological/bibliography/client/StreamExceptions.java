package uk.co.terminological.bibliography.client;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * also see https://github.com/diffplug/durian/blob/v2.0/src/com/diffplug/common/base/Errors.java
 * @author terminological
 *
 */
public class StreamExceptions {

	static Logger log = LoggerFactory.getLogger(StreamExceptions.class);
	
	//Functions
	
	@FunctionalInterface
	public static interface FunctionWithException<T, R, E extends Exception> {
		R apply(T t) throws E;
	}

	public static <T, R, E extends Exception> Function<T, R> rethrow(FunctionWithException<T, R, E> fe) {
		return arg -> {
			try {
				return fe.apply(arg);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		};
	}

	public static <T, R, E extends Exception> Function<T, Optional<R>> ignore(FunctionWithException<T, R, E> fe) {
		return arg -> {
			try {
				return Optional.of(fe.apply(arg));
			} catch (Exception e) {
				return Optional.empty();
			}
		};
	}
	
	public static <T, R, E extends Exception> Function<T, Optional<R>> ignoreFunction(FunctionWithException<T, R, E> fe) {
		return ignore(fe);
	}
	
	public static <T, R> Function<T, Optional<R>> ignoreRuntime(Function<T, R> fe) {
		return arg -> {
			try {
				return Optional.of(fe.apply(arg));
			} catch (Exception e) {
				return Optional.empty();
			}
		};
	}
	
	public static <T, R, E extends Exception> Function<T, Optional<R>> logWarn(FunctionWithException<T, R, E> fe) {
		return arg -> {
			try {
				return Optional.of(fe.apply(arg));
			} catch (Exception e) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				e.printStackTrace(new PrintStream(baos));
				log.warn(baos.toString());
				return Optional.empty();
			}
		};
	}

	// Consumer
	@FunctionalInterface
	public static interface ConsumerWithException<T, E extends Exception> {
		void accept(T t) throws E;
	}

	public static <T, E extends Exception> Consumer<T> rethrow(ConsumerWithException<T, E> fe) {
		return new Consumer<T>() {
			@Override
			public void accept(T t) {
				try {
					fe.accept(t);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}		
			}
		};
	}

	public static <T, E extends Exception> Consumer<T> ignore(ConsumerWithException<T, E> fe) {
		return arg -> {
			try {
				fe.accept(arg);
			} catch (Exception e) {
				//do nothing;
			}
		};
	}
	
	public static <T, E extends Exception> Consumer<T> logWarn(ConsumerWithException<T, E> fe) {
		return arg -> {
			try {
				fe.accept(arg);
			} catch (Exception e) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				e.printStackTrace(new PrintStream(baos));
				log.warn(baos.toString());
			}
		};
	}
	
	@FunctionalInterface
	public static interface SupplierWithException<T, E extends Exception> {
		T get() throws E;
	}

	public static <T, E extends Exception> Supplier<T> rethrow(SupplierWithException<T, E> fe) {
		return new Supplier<T>() {
			@Override
			public T get() {
				try {
					return fe.get();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}		
			}
		};
	}

	public static <T, E extends Exception> Supplier<Optional<T>> ignore(SupplierWithException<T, E> fe) {
		return () -> {
			try {
				return Optional.ofNullable(fe.get());
			} catch (Exception e) {
				return Optional.empty();
				//do nothing;
			}
		};
	}
	
	public static <T, E extends Exception> Supplier<Optional<T>> logWarn(SupplierWithException<T, E> fe) {
		return () -> {
			try {
				return Optional.ofNullable(fe.get());
			} catch (Exception e) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				e.printStackTrace(new PrintStream(baos));
				log.warn(baos.toString());
				return Optional.empty();
			}
		};
	}
	
	public static <T, E extends Exception> void tryIgnore(ConsumerWithException<T, E> fe) {
		ignore(fe).accept(null);
	}
	
	public static <T, E extends Exception> void tryIgnore(T val, ConsumerWithException<T, E> fe) {
		ignore(fe).accept(val);
	}
	
	public static <T, E extends Exception> Optional<T> tryIgnore(SupplierWithException<T, E> fe) {
		return ignore(fe).get();
	}
	
	
	public static <T, E extends Exception> void tryRethrow(ConsumerWithException<T, E> fe) {
		rethrow(fe).accept(null);
	}
	
	public static <T, E extends Exception> void tryRethrow(T val, ConsumerWithException<T, E> fe) {
		rethrow(fe).accept(val);
	}
	
	public static <T, E extends Exception> T tryRethrow(SupplierWithException<T, E> fe) {
		return rethrow(fe).get();
	}
	
	public static <T, R, E extends Exception> R tryDoRethrow(T val, FunctionWithException<T, R, E> fe) {
		return rethrow(fe).apply(val);
	}
	
	public static <T, E extends Exception> void tryLogWarn(ConsumerWithException<T, E> fe) {
		logWarn(fe).accept(null);
	}
	
	public static <T, E extends Exception> void tryLogWarn(T val, ConsumerWithException<T, E> fe) {
		logWarn(fe).accept(val);
	}
	
	public static <T, E extends Exception> Optional<T> tryLogWarn(SupplierWithException<T, E> fe) {
		return logWarn(fe).get();
	}
	//TODO: Other functional interfaces.
}
