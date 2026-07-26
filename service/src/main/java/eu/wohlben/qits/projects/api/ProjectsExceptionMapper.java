package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.error.DomainException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps the projects domain's framework-free {@link DomainException}s (each carrying a status code)
 * to HTTP responses.
 *
 * <p>It lives here, in {@code service}, for the same reason the sibling {@code EpicsExceptionMapper}
 * does: the {@code domain} module carries no JAX-RS, which is what lets it stay a plain library jar.
 *
 * <p>Not inherited from anywhere. The monorepo's {@code eu.wohlben.qits.api.DomainExceptionMapper}
 * is monolith-only (migration-plan.md §3.9), so without this class every {@code BadRequestException}
 * this context throws would surface as a 500 where the suite — and the frontend — expect a 400.
 *
 * <p>Scoped to <em>this</em> context's exception type. An application that also runs the monorepo's
 * {@code eu.wohlben.qits.domain.error.DomainException}, or qits-workspaces' or epics' equivalents,
 * keeps its own mapper for each; they coexist because they map unrelated types.
 */
@Provider
public class ProjectsExceptionMapper implements ExceptionMapper<DomainException> {

  @Override
  public Response toResponse(DomainException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    return Response.status(status)
        .entity(Map.of("message", message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
