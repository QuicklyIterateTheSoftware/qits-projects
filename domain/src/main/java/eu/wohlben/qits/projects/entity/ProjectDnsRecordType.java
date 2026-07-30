package eu.wohlben.qits.projects.entity;

/**
 * The record types a project's domain configuration may name — the three qits-dns accepts for a
 * deployment target, and deliberately not its whole type set (there is no project-level meaning for
 * an MX or a TXT).
 *
 * <p>Stored as a string in the {@code dns_type} column (see {@link ProjectDnsRecord}) so the
 * spelling on the wire, in the database and in qits-dns' own {@code DnsRecordType} are one word —
 * the payload travels between the three unchanged.
 */
public enum ProjectDnsRecordType {
  A,
  AAAA,
  CNAME
}
