package eu.wohlben.qits.domain.repository.mcp;

import eu.wohlben.qits.domain.error.BadRequestException;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Resolves the scope an MCP session is bound to. Every repository MCP tool runs against exactly one
 * project, and may be further narrowed to a single repository within it; the ids are taken from the
 * connection's HTTP request rather than from a tool argument, so the model has no parameter it
 * could point at another project/repository and cannot widen its own scope.
 *
 * <p>The project id is read from the {@code X-QITS-Project} header (sent on every request by
 * clients configured with a static header, e.g. {@code claude mcp add --header}); a {@code
 * ?projectId=} query parameter is accepted as a fallback. An <em>optional</em> {@code
 * X-QITS-Repository} header (or {@code ?repositoryId=} fallback) narrows the session to one
 * repository — absent, the whole project is in scope. {@code HttpServerRequest} is request-scoped
 * and only available on the HTTP-based transports (Streamable HTTP / SSE), which is what this
 * server uses.
 */
@RequestScoped
public class ProjectScope {

  /** Header carrying the project id the connection is scoped to. */
  public static final String PROJECT_HEADER = "X-QITS-Project";

  /** Query-parameter fallback when a client cannot set a custom header. */
  public static final String PROJECT_QUERY_PARAM = "projectId";

  /** Optional header narrowing the session to a single repository within the project. */
  public static final String REPOSITORY_HEADER = "X-QITS-Repository";

  /** Query-parameter fallback for the optional repository narrowing. */
  public static final String REPOSITORY_QUERY_PARAM = "repositoryId";

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

  /**
   * The single repository this session is narrowed to within its project, or empty when the whole
   * project is in scope. When present, tools may only touch this one repository.
   */
  public Optional<String> repositoryId() {
    String repositoryId = request.getHeader(REPOSITORY_HEADER);
    if (repositoryId == null || repositoryId.isBlank()) {
      repositoryId = request.getParam(REPOSITORY_QUERY_PARAM);
    }
    if (repositoryId == null || repositoryId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(repositoryId.trim());
  }
}
