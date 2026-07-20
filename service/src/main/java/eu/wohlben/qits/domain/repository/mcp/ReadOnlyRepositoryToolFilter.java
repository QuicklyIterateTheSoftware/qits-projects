package eu.wohlben.qits.domain.repository.mcp;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Hides the <em>mutating</em> repository tools from a session that connected read-only — the {@code
 * agentReadOnly=true} marker {@code AgentLaunchService.renderAutonomous} stamps into the MCP URL of
 * an <strong>autonomous</strong> run. An unattended {@code claude -p
 * --dangerously-skip-permissions} run (today only conflict resolution) attaches the repository
 * server solely for {@code taskPrompt}; it must not be able to drive host-side, cross-repository
 * mutations (create workspaces, integrate or clean up branches) with no human in the loop — its own
 * git work happens inside its container. Interactive/chat sessions do not set the marker, so their
 * tool set is unchanged.
 *
 * <p>Mirrors {@link TaskPromptToolFilter}: fails <em>closed</em> (hide the mutating tool) if the
 * request scope can't be read, so a listing error never widens access.
 */
@ApplicationScoped
public class ReadOnlyRepositoryToolFilter implements ToolFilter {

  /**
   * Query-parameter marker set by an autonomous (unattended) launch to request a read-only view.
   */
  public static final String READ_ONLY_PARAM = "agentReadOnly";

  /**
   * The mutating tools of the "repository" MCP server (see {@code RepositoryMcpTools}); everything
   * else it exposes is read-only. Kept explicit so a newly added mutating tool is a conscious
   * choice to add here. {@code runAction} executes a configured action script in a workspace
   * container — a host-side side effect — so it must be hidden from an unattended read-only run
   * just like the branch/workspace mutators, else a conflict-resolution agent steered by an
   * untrusted commit message could run arbitrary actions with no human in the loop.
   */
  private static final Set<String> MUTATING_TOOLS =
      Set.of(
          "createWorkspace",
          "cleanupBranch",
          "integrateBranch",
          "mergeParentIntoWorkspace",
          "runAction");

  @Inject HttpServerRequest request;

  @Override
  public boolean test(ToolInfo tool, McpConnection connection) {
    if (!MUTATING_TOOLS.contains(tool.name())) {
      return true;
    }
    try {
      return !"true".equalsIgnoreCase(request.getParam(READ_ONLY_PARAM));
    } catch (RuntimeException e) {
      return false;
    }
  }
}
