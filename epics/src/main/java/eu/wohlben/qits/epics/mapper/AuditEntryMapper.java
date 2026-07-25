package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.AuditEntryDto;
import eu.wohlben.qits.epics.entity.AuditEntry;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface AuditEntryMapper {
  AuditEntryDto toDto(AuditEntry entity);
}
