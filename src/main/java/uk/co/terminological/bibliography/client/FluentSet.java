/**
 * 
 */
package uk.co.terminological.bibliography.client;

import java.util.Collection;
import java.util.HashSet;


/**
 * @author RCHALLEN
 *
 */
public class FluentSet<S> extends HashSet<S> {
	
	private static final long serialVersionUID = 1L;
	
	@SafeVarargs
	public static <T> FluentSet<T> create(T... args) {
		FluentSet<T> out = new FluentSet<T>();
		for (T arg: args) {
			out.add(arg);
		}
		return out;
	}
	
	public static <T> FluentSet<T> create(Class<T> type) {
		FluentSet<T> out = new FluentSet<T>();
		return out;
	}
	
	public static <T> FluentSet<T> with(T arg) {
		FluentSet<T> out = new FluentSet<T>();
		out.add(arg);
		return out;
	}
	
	@SafeVarargs
	public static <T> FluentSet<T> create(Collection<T>... args) {
		FluentSet<T> out = new FluentSet<T>();
		for (Collection<T> arg: args) {
			out.addAll(arg);
		}
		return out;
	}
	
	public static <T> FluentSet<T> empty() {
		return new FluentSet<T>();
	}
	
	public FluentSet<S> and(S element) {
		this.add(element);
		return this;
	}
	
	public FluentSet<S> append(Collection<S> element) {
		if (element != null) this.addAll(element);
		return this;
	}
}