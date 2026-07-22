package eu.wohlben.qits.domain.repository.mcp;

import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.entity.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The scoping guard shared by every tool class mounted on the "repository" MCP server ({@link
 * RepositoryMcpTools}): resolves the session's {@link ProjectScope} and rejects any repository
 * outside it, so no tool can operate across project boundaries.
 */
@ApplicationScoped
public class ProjectScopeGuard {

  @Inject ProjectScope scope;

  @Inject ProjectService projectService;

  /**
   * Ensures {@code repoId} names a repository inside the project this session is scoped to. When
   * the session is narrowed to a single repository, also rejects any other repository in the
   * project. Throws {@link NotFoundException} otherwise (also covering a non-existent repository).
   */
  public Repository requireRepoInProject(String repoId) {
    var scopedRepo = scope.repositoryId();
    if (scopedRepo.isPresent() && !scopedRepo.get().equals(repoId)) {
      throw new NotFoundException("Repository not in this session's scope: " + repoId);
    }
    return projectService.getRepositories(scope.requireProjectId()).stream()
        .filter(r -> r.id.equals(repoId))
        .findFirst()
        .orElseThrow(
            () -> new NotFoundException("Repository not found in this project: " + repoId));
  }
}
