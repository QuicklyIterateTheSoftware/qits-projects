package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.EpicDto;
import eu.wohlben.qits.epics.entity.Epic;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface EpicMapper {
  EpicDto toDto(Epic entity);
}
