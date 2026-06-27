package eu.wohlben.qits.domain.repository.dto;

import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;

public record RepositoryDto(
    String id, String url, String mainBranch, RepositoryArchetype archetype, String projectId) {}
