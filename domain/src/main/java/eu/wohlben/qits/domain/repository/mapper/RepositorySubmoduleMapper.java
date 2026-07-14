package eu.wohlben.qits.domain.repository.mapper;

import eu.wohlben.qits.domain.repository.dto.RepositorySubmoduleDto;
import eu.wohlben.qits.domain.repository.entity.RepositorySubmodule;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface RepositorySubmoduleMapper {

  @Mapping(target = "parentRepoId", source = "parent.id")
  @Mapping(target = "childRepoId", source = "child.id")
  RepositorySubmoduleDto toDto(RepositorySubmodule entity);
}
