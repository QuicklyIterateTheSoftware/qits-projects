package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;

/**
 * A repository as clients see it.
 *
 * @param name the project-scoped addressable name — what {@code /git/<projectId>/<name>} serves and
 *     what a committed {@code ../<name>.git} resolves to. This is the repository's identity to a
 *     person; {@code id} is an opaque key. Null only for a row that somehow owns no alias.
 */
public record RepositoryDto(
    String id,
    String name,
    String url,
    String mainBranch,
    RepositoryArchetype archetype,
    String projectId) {}
