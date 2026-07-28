package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code repository.json} sidecar written beside every bare origin — what repository discovery
 * restores a row's {@code url} and {@code archetype} from when the database no longer has it.
 *
 * <p><b>{@code @RegisterForReflection} is load-bearing.</b> Quarkus registers the types it can see a
 * route reach — JAX-RS bodies, MCP tool arguments — and this is neither: {@link MetadataService}
 * hands it straight to a raw {@code ObjectMapper}. In a native image Jackson then finds no
 * constructor and no fields, and {@code RepositoryDiscoveryService} fails during startup with
 * "cannot deserialize from Object value", so the process does not come up at all against any data
 * directory that has ever held a repository. A JVM run sees none of that — reflection over a public
 * class always works there — which is exactly why the annotation belongs on the type rather than in
 * a config file someone would have to think to look at.
 */
@RegisterForReflection
public class RepositoryMetadata {
  public String id;
  public String url;
  public RepositoryArchetype archetype;
}
