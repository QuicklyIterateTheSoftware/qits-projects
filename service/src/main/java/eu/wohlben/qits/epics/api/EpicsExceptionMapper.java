package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.error.EpicsException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps epics' framework-free {@link EpicsException}s (carrying a status code) to HTTP responses —
 * the sibling of {@code DomainExceptionMapper}/{@code ArtifactsExceptionMapper}, kept here in
 * {@code service} because the epics module carries no JAX-RS (same stance as {@code domain}).
 */
@Provider
public class EpicsExceptionMapper implements ExceptionMapper<EpicsException> {

  @Override
  public Response toResponse(EpicsException exception) {
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
