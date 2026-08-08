package eu.wohlben.qits.epics.dto;

import java.time.Instant;

public record FeatureDto(
    String id,
    String epicId,
    String title,
    String slug,
    String description,
    String dependsOnFeatureId,
    Instant implementedOn,
    Instant createdAt,
    Instant updatedAt) {}
