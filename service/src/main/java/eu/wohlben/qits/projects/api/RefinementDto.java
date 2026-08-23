package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.refinementhost.RefinementService;
import java.time.Instant;

/**
 * One refinement as the refining route reads it — the row plus the live halves, field names kept
 * deliberately in step with the workspace projection the SPA's status strip already consumes
 * ({@code runtimeStatus}, {@code clean}, {@code ahead}, {@code daemonConnectedAt}, …), so the
 * cutover moves a base URL rather than a vocabulary.
 *
 * <p>{@code runtimeStatus} is one of {@code RUNNING | STOPPED | PROVISIONING | FAILED} — the
 * published strings the strip switches on. {@code clean} is three-valued: {@code null} is "the
 * daemon has not vouched", which blocks a recreate. {@code daemonOutdated} is {@code TRUE} or
 * {@code null}, never {@code false} — "outdated" is a claim, its absence is not one.
 */
public record RefinementDto(
    Long id,
    String epicId,
    String projectId,
    String repositoryId,
    String branch,
    String parent,
    String label,
    String preamble,
    String runtimeStatus,
    String runtimeError,
    Boolean clean,
    Integer ahead,
    Integer behind,
    boolean conflictsWithParent,
    String agentActivity,
    Instant daemonConnectedAt,
    String daemonVersion,
    Boolean daemonOutdated,
    Instant createdAt) {

  public static RefinementDto of(RefinementService.RefinementView view) {
    return new RefinementDto(
        view.refinement().id,
        view.refinement().epicId,
        view.refinement().projectId,
        view.refinement().repositoryId,
        view.refinement().branch,
        view.refinement().parent,
        view.refinement().label,
        view.refinement().preamble,
        view.runtimeStatus(),
        view.runtimeError(),
        view.clean(),
        view.ahead(),
        view.behind(),
        view.conflictsWithParent(),
        view.agentActivity(),
        view.daemonConnectedAt(),
        view.daemonVersion(),
        view.daemonOutdated(),
        view.refinement().createdAt);
  }
}
