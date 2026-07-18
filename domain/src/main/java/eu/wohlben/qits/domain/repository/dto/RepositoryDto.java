package eu.wohlben.qits.domain.repository.dto;

import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;

/**
 * {@code configWarning} carries the last {@code .qits-config.yml} ingestion problem (parse or
 * per-entry validation failure), or {@code null} when the file is absent or ingested cleanly — the
 * detail view renders it as a repository-level warning.
 */
public record RepositoryDto(
    String id,
    String url,
    String mainBranch,
    RepositoryArchetype archetype,
    String configWarning,
    String projectId) {}
