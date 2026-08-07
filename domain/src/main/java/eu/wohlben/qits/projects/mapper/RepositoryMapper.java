package eu.wohlben.qits.projects.mapper;

import eu.wohlben.qits.projects.dto.LastBackupDto;
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

  /**
   * The entity's column is still called {@code url}; the DTO's field is {@code backupUrl}, which is
   * what it has always meant. The column keeps its name because renaming it is a migration that buys
   * nothing — this mapping is where the two spellings meet.
   */
  @Mapping(target = "projectId", source = "project.id")
  @Mapping(target = "backupUrl", source = "url")
  @Mapping(target = "lastBackup", expression = "java(lastBackupOf(entity))")
  @Mapping(
      target = "name",
      expression = "java(entity == null ? null : repositoryNames.nameFor(entity).orElse(null))")
  public abstract RepositoryDto toDto(Repository entity);

  /**
   * The backup status block, or null when this repository has never been backed up. Null rather than
   * an object with null fields: "never attempted" is one fact, and a client should not have to read
   * three fields to learn it.
   */
  protected static LastBackupDto lastBackupOf(Repository entity) {
    if (entity == null || entity.lastBackupOutcome == null) {
      return null;
    }
    return new LastBackupDto(
        entity.lastBackupOutcome, entity.lastBackupAt, entity.lastBackupDetail);
  }
}
