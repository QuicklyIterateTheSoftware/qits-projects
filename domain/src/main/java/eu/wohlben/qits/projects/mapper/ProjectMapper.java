package eu.wohlben.qits.projects.mapper;

import eu.wohlben.qits.projects.dto.ProjectDto;
import eu.wohlben.qits.projects.entity.Project;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface ProjectMapper {

  ProjectDto toDto(Project entity);
}
