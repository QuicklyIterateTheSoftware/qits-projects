package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.Refinement;
import java.util.Optional;

/**
 * The container verbs the refinement lifecycle needs, as a seam — the refinement twin of
 * {@code agenthost/ContainerRuntime}, and like it a port so the suite can install a fake and reach
 * no orchestrator. The sole implementation is {@code containershost/ContainersRefinementRuntime}.
 *
 * <p><b>The place is {@code owner/refinement/<rowId>}.</b> The row id is minted by this database,
 * is unique forever, and is already a legal orchestrator ref — so unlike qits-workspaces there is
 * no name-derived ref and no hash disambiguator to need: the container <em>name</em>
 * ({@code qits-ref-<projectSlug>-<epicSlug>}) is a {@code docker ps} hint carried as
 * {@code explicitName}, never an address. What a human-derived name can still do is collide, and
 * the provisioning arm answers that with a 409 rather than a constraint 500.
 *
 * <p>Unlike the agent runtime this one has a removal verb: a refinement is discarded when its epic
 * leaves refinement, and the teardown order — container first, then volume — is the wire contract.
 */
public interface RefinementRuntime {

  /** One container as this lifecycle reads it. */
  record ContainerInfo(String containerName, boolean running) {}

  /** What is at this refinement's place, or empty when the orchestrator holds no row for it. */
  Optional<ContainerInfo> inspect(long refinementId);

  /**
   * Bring a fresh container up — the arm that commissions. Throws when no running container could
   * be produced; a 2xx whose observed state is MISSING/GONE is a failed launch, not a retry case.
   */
  void provision(Refinement refinement, String projectSlug, String epicSlug, String wrapperName);

  /** Wake a stopped container — a start in place, a replacement only if the spec really changed. */
  void wake(Refinement refinement, String projectSlug, String epicSlug, String wrapperName);

  /** Stop the container gracefully, leaving it and its volume in place. Best-effort. */
  void stop(long refinementId);

  /** Stamp the orchestrator's idle clock — a no-op unless a policy reads it. Best-effort. */
  void touch(long refinementId);

  /** Remove the container (never its volumes with it), then the volume. Idempotent. */
  void delete(long refinementId);
}
