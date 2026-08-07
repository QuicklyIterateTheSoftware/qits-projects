package eu.wohlben.qits.projects.mapper;

import eu.wohlben.qits.projects.dto.RepositoryDto;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import jakarta.inject.Inject;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * An abstract class rather than an interface, because {@code name} does not live on the entity: a
 * repository's addressable name is a row in the alias table, and reading it needs a collaborator.
 * Carrying it here rather than at each call site is what let every client drop its own
 * derive-a-label-from-the-url hack.
 */
@Mapper(componentModel = "jakarta")
public abstract class RepositoryMapper {

  @Inject RepositoryNameRepository repositoryNames;

  @Mapping(target = "projectId", source = "project.id")
  @Mapping(
      target = "name",
      expression = "java(entity == null ? null : repositoryNames.nameFor(entity).orElse(null))")
  public abstract RepositoryDto toDto(Repository entity);
}
