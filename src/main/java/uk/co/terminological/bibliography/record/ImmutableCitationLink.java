package uk.co.terminological.bibliography.record;

import java.io.Serializable;
import java.util.Optional;

public class ImmutableCitationLink implements Serializable, CitationLink {

	ImmutableCitationReference source;
	ImmutableCitationReference target;
	Integer index;
	
	public ImmutableCitationLink(CitationLink cr) {
		this.source = Builder.citationReference(cr.getSource());
		this.target = Builder.citationReference(cr.getTarget());
		this.index = cr.getIndex().orElse(null);
	}
	
	public ImmutableCitationLink(
			CitationReference source,
			CitationReference target, 
			Optional<Integer> of) {
		this.source = Builder.citationReference(source);
		this.target = Builder.citationReference(target);
		// the index was accepted and dropped, which lost the position of every
		// reference in a citation list
		this.index = of.orElse(null);
	}

	@Override
	public ImmutableCitationReference getSource() {
		return source;
	}

	@Override
	public ImmutableCitationReference getTarget() {
		return target;
	}

	@Override
	public Optional<Integer> getIndex() {
		return Optional.ofNullable(index);
	}

}
