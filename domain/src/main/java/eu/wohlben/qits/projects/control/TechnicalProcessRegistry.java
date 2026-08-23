package eu.wohlben.qits.projects.control;

import java.util.Optional;

/**
 * The application's registry of live, streamable technical processes, narrowed to what the projects
 * context asks of it: hold a repository for a pull/push/sync walk, hold it for an interactive
 * remote-login terminal, and answer which operation is live for a repository.
 *
 * <p><strong>A port, not an implementation.</strong> The technical-process framework is a
 * cross-context streaming primitive — it also carries workspace provisioning and container starts,
 * and its in-memory registry, retention window and SSE fan-out live in {@code domain.process},
 * which belongs to qits-workspace-daemon (migration-plan.md §3.3). qits-workspaces cut the same
 * seam from its side as {@code WorkspaceProcessTracker}; this is the repository-scoped half of the
 * same framework, declared here so this jar carries no process code.
 *
 * <p>Injected as {@code Instance<TechnicalProcessRegistry>} and <strong>genuinely optional</strong>,
 * matching the workspace-daemon SPI precedent rather than the mandatory {@code RepositoryLookup}
 * one. With no implementation present every operation still happens, on the same worker thread,
 * against the same bare origins, with the same result — only the streamed narration is absent, the
 * returned process id is {@code null} (the pull/push/sync responses already permit it), and the
 * single-flight guard degrades to "always fresh". Absent is therefore a supported, documented
 * configuration: an application that wants the narration implements this interface, one that does
 * not still gets working repositories.
 */
public interface TechnicalProcessRegistry {

  /**
   * Register an operation scoped to nothing but its own id — the refinement container ensure's
   * shape, where the caller owns the single-flight itself (one lock per refinement row) and only
   * needs a subscribable narration. {@code kind} is a label for the log, not a key.
   */
  TechnicalProcess begin(String kind);

  /**
   * Register a repository-scoped operation of {@code kind} ({@code pull}/{@code push}/{@code
   * sync}), before any git runs — so the currently-fetching repo is visible while its {@code git
   * fetch} blocks on the network.
   */
  RepoProcessLease beginForRepository(String repoId, String kind);

  /**
   * Hold {@code repoId} for something that is not a narrated process (the remote-login terminal),
   * so a pull cannot start underneath an interactive sign-in and vice versa.
   */
  RepoReservation reserveRepository(String repoId, String kind);

  /** Release a {@link RepoReservation.Acquired} hold. Idempotent for an unknown token. */
  void releaseRepository(String repoId, String token);

  /** The process behind {@code id}, while it is still subscribable. */
  Optional<TechnicalProcess> find(String id);

  /** The id of the operation currently live for this repository, if any. */
  Optional<String> activeForRepository(String repoId);
}
