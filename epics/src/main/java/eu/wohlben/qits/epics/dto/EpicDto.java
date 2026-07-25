package eu.wohlben.qits.epics.dto;

import java.time.Instant;

public record EpicDto(
    String id,
    String projectId,
    String title,
    String description,
    Instant createdAt,
    Instant updatedAt) {}
