package eu.wohlben.qits.domain.repository.mcp;

import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.dto.RepositorySubmoduleDto;
import eu.wohlben.qits.domain.repository.mapper.RepositorySubmoduleMapper;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * The submodule tool of the "repository" MCP server. When qits imports a repository with
 * submodules, each distinct submodule is imported as a sibling repository under the same project
 * and linked by an edge; this lists those edges for a superproject (parent → child at a mount
 * path). Scoping and error mapping follow {@link RepositoryMcpTools}: {@code repoId} is checked by
 * {@link ProjectScopeGuard} against the session's project and {@link WrapBusinessError} renders
 * domain exceptions.
 */
@ApplicationScoped
@WrapBusinessError
public class RepositorySubmoduleMcpTools {

  @Inject ProjectScopeGuard scopeGuard;

  @Inject RepositoryService repositoryService;

  @Inject RepositorySubmoduleMapper repositorySubmoduleMapper;

  @McpServer("repository")
  @Tool(
      description =
          "List a repository's submodules — the sibling repositories qits imported for its"
              + " .gitmodules entries. Each edge gives the child repository id and the mount path"
              + " within the superproject working tree. Use the childRepoId with the other"
              + " repository tools to inspect a submodule as a repository in its own right.")
  @Transactional
  public List<RepositorySubmoduleDto> listSubmodules(
      @ToolArg(description = "id of a repository in this project") String repoId) {
    scopeGuard.requireRepoInProject(repoId);
    return repositoryService.listSubmodules(repoId).stream()
        .map(repositorySubmoduleMapper::toDto)
        .toList();
  }
}
