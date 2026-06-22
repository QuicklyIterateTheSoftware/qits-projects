package eu.wohlben.qits.domain.repository.mapper;

import eu.wohlben.qits.domain.repository.dto.RepositoryDto;
import eu.wohlben.qits.domain.repository.entity.Repository;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface RepositoryMapper {

  @Mapping(target = "projectId", source = "project.id")
  RepositoryDto toDto(Repository entity);
}
