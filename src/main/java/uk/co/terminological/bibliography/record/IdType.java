package uk.co.terminological.bibliography.record;

/**
 * The identifier schemes a work can be named in.
 *
 * Which scheme an identifier belongs to is part of the identifier: "30636200" is a PMID and means nothing without
 * that, and the same work carries a different string in each scheme. This is why identity in the model is always a
 * (type, value) pair, and why {@link RecordReferenceMapping} exists to relate them.
 *
 * {@code UNK} is not a failure case but a deliberate one: OpenCitations names works by {@code omid:} and other
 * handles that this model does not model, and keeping such a handle as an unknown-scheme identifier is better than
 * dropping the reference that carried it.
 *
 * The principle behind the membership, argued once in
 * {@code uk.co.terminological.bibliography.api.opencitations.TestIdTypeMembership}: <strong>these are the schemes
 * sources actually emit</strong> — nothing speculative. {@code MID} was declared and constructed by nothing, and a
 * value that exists for nothing costs every reader of this enum a question; it is gone, and a reference with no
 * identifier says {@code UNK} like every other scheme-less case (debt-011).
 */
public enum IdType { PMCID, PMID, DOI, ISBN, OPENALEX, ARXIV, UNK }