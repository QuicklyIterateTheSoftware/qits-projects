package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;

public record RepositoryDto(
    String id, String url, String mainBranch, RepositoryArchetype archetype, String projectId) {}
