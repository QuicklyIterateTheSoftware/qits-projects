package eu.wohlben.qits.epics.dto;

import java.time.Instant;

public record TaskDto(
    String id,
    String featureId,
    String repositoryId,
    String title,
    String description,
    String dependsOnTaskId,
    Instant implementedAt,
    Instant createdAt,
    Instant updatedAt) {}
