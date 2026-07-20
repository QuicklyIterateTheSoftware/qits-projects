package eu.wohlben.qits.domain.repository.mcp;

import eu.wohlben.qits.domain.telemetry.mcp.WorkspaceScope;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Exposes the {@code taskPrompt} tool only to sessions scoped all the way down to a workspace
 * (repository <em>and</em> workspace narrowing present): the prompt draft it serves is
 * per-workspace, so a broader session has nothing it could fetch. Mirrors {@link
 * eu.wohlben.qits.domain.telemetry.mcp.TelemetryToolFilter}, fails closed.
 */
@ApplicationScoped
public class TaskPromptToolFilter implements ToolFilter {

  /** The workspace-scoped tools of the "repository" MCP server (see {@link TaskPromptMcpTools}). */
  private static final Set<String> WORKSPACE_TOOLS = Set.of("taskPrompt");

  @Inject ProjectScope projectScope;

  @Inject WorkspaceScope workspaceScope;

  @Override
  public boolean test(ToolInfo tool, McpConnection connection) {
    if (!WORKSPACE_TOOLS.contains(tool.name())) {
      return true;
    }
    // Fail closed: if the request scope can't be read, hide the tool rather than letting the
    // listing error.
    try {
      return projectScope.repositoryId().isPresent() && workspaceScope.hasWorkspace();
    } catch (RuntimeException e) {
      return false;
    }
  }
}
