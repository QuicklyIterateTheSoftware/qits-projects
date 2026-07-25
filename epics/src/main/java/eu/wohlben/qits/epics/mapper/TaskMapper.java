package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.TaskDto;
import eu.wohlben.qits.epics.entity.Task;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface TaskMapper {
  TaskDto toDto(Task entity);
}
