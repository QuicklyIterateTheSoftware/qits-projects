package eu.wohlben.qits.projects.dto;

/**
 * @param slug the git-safe, immutable identity the project's wrapper repository is named after
 *     ({@code <slug>-<slug>}). Distinct from the editable display {@code name}, and never changes.
 */
public record ProjectDto(String id, String name, String slug, String description) {}
