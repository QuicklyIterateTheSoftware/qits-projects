package eu.wohlben.qits.domain.project.mapper;

import eu.wohlben.qits.domain.project.dto.ProjectDto;
import eu.wohlben.qits.domain.project.entity.Project;
import org.mapstruct.Mapper;

@Mapper(componentModel = "cdi")
public interface ProjectMapper {

    ProjectDto toDto(Project entity);
}
