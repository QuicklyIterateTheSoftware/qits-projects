package eu.wohlben.qits.domain.repository.mcp;

import eu.wohlben.qits.domain.error.BadRequestException;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * Resolves the single project an MCP session is scoped to. Every repository MCP tool runs against
 * exactly one project; the id is taken from the connection's HTTP request rather than from a tool
 * argument, so the model has no parameter it could point at another project and cannot widen its
 * own scope.
 *
 * <p>The id is read from the {@code X-QITS-Project} header (sent on every request by clients
 * configured with a static header, e.g. {@code claude mcp add --header}); a {@code ?projectId=}
 * query parameter is accepted as a fallback. {@code HttpServerRequest} is request-scoped and only
 * available on the HTTP-based transports (Streamable HTTP / SSE), which is what this server uses.
 */
@RequestScoped
public class ProjectScope {

  /** Header carrying the project id the connection is scoped to. */
  public static final String PROJECT_HEADER = "X-QITS-Project";

  /** Query-parameter fallback when a client cannot set a custom header. */
  public static final String PROJECT_QUERY_PARAM = "projectId";

  @Inject HttpServerRequest request;

  /**
   * The scoped project id, or throws when the connection didn't supply one. Used by every tool to
   * bound its work to a single project.
   */
  public String requireProjectId() {
    String projectId = request.getHeader(PROJECT_HEADER);
    if (projectId == null || projectId.isBlank()) {
      projectId = request.getParam(PROJECT_QUERY_PARAM);
    }
    if (projectId == null || projectId.isBlank()) {
      throw new BadRequestException(
          "This MCP session is not scoped to a project. Connect with the '"
              + PROJECT_HEADER
              + "' header (or '?"
              + PROJECT_QUERY_PARAM
              + "=' query parameter) set to a project id.");
    }
    return projectId.trim();
  }
}
