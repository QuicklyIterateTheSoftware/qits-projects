package eu.wohlben.qits.projects.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Test-only: reports what the request resolved to, so the header contract can be asserted directly
 * rather than inferred from something that happens to write an audit row.
 *
 * <p>Served under {@code /projects/api/test-identity} — the suite inherits the shipped {@code
 * quarkus.rest.path=/projects/api}, so this fixture is addressed exactly as a real route is.
 *
 * <p>Hidden from the OpenAPI document. {@code OpenApiSchemaExportTest} generates {@code
 * docs/openapi.yml} from a running {@code @QuarkusTest}, which indexes the test classpath too —
 * so without this a test fixture would be published in the committed API document as if it were
 * a real operation, and any client generated from that document would grow a method for an
 * endpoint no deployment serves.
 */
@Path("/test-identity")
@Produces(MediaType.APPLICATION_JSON)
public class IdentityEchoResource {

  @Inject SecurityIdentity identity;

  public record Identity(boolean anonymous, String principal, Set<String> roles) {}

  @GET
  @Operation(hidden = true)
  public Identity get() {
    return new Identity(
        identity.isAnonymous(),
        identity.getPrincipal() == null ? null : identity.getPrincipal().getName(),
        identity.getRoles());
  }
}
