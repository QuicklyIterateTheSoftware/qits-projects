package eu.wohlben.qits.projects.mapper;

import eu.wohlben.qits.projects.dto.RepositoryDto;
import eu.wohlben.qits.projects.entity.Repository;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface RepositoryMapper {

  @Mapping(target = "projectId", source = "project.id")
  RepositoryDto toDto(Repository entity);
}
