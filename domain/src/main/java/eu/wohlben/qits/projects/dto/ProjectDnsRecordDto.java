package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;

/**
 * A project's stored dns record on the wire — the same three fields as the embeddable, carried out
 * unchanged.
 *
 * <p>The whole object is {@code null} on a {@link ProjectDto} whose project registers no domain
 * (rows predating the feature, or a self-seed with no domain configured). It is never an object
 * with three null fields: that would be a third state to read, and there are only two.
 *
 * @param domain the whole fully-qualified name, never zone-relative
 * @param value the address or CNAME target — present for every type
 */
public record ProjectDnsRecordDto(String domain, ProjectDnsRecordType type, String value) {}
