package eu.wohlben.qits.epics.dto;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import java.time.Instant;

public record AuditEntryDto(
    String id,
    AuditEntityType entityType,
    String entityId,
    String epicId,
    AuditOperation operation,
    String changedBy,
    Instant changedAt,
    String snapshot) {}
