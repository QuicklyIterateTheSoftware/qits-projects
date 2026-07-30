package eu.wohlben.qits.projects.dto;

/**
 * @param slug the git-safe, immutable identity the project's wrapper repository is named after
 *     ({@code <slug>-<slug>}). Distinct from the editable display {@code name}, and never changes.
 * @param dns the domain this project resolves through, or {@code null} when it registers none —
 *     required on creation, but nullable here because rows created before the field existed and
 *     self-seeded projects without a configured domain both legitimately have no record.
 */
public record ProjectDto(
    String id, String name, String slug, String description, ProjectDnsRecordDto dns) {}
