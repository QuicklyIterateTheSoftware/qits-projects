package eu.wohlben.qits.domain.seeding.control;

import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.entity.RepositorySubmodule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reconciles a packaged qits deployment into a seeded "qits" project holding the qits repositories
 * themselves — the startup counterpart to the cli {@code seed}/{@code seed-webapp} demos (see
 * {@code docs/epics/qits-live-deployment/features/2026-07-19_startup-qits-self-seed.md}). Where
 * those seeds define a demo world programmatically, this one only registers the real repositories:
 * their daemons, actions and bootstrap chain all arrive declaratively from each repo's committed
 * {@code .qits-config.yml} on clone.
 *
 * <p>The seed is a small in-code {@linkplain #manifest() manifest} — the project name plus an
 * ordered list of desired repositories — <b>reconciled additively on every boot</b>. Growing the
 * set of registered qits repositories is a matter of appending a manifest entry; the next
 * deployment's reconcile adds exactly the missing one to the already-seeded project. Reconciliation
 * drives the real domain services (not raw SQL), so it always matches the current model.
 *
 * <p><b>Idempotency is per item, not per seed</b>, and reconciliation is strictly additive — it
 * never deletes or modifies rows it finds (user-added repositories, renamed projects and
 * hand-edited config all survive):
 *
 * <ul>
 *   <li>The project (named {@value #PROJECT_NAME}) is created if absent, matched by name otherwise.
 *   <li>Each manifest repository is matched by clone url within the project, created via {@link
 *       ProjectService#createRepositoryUnderProject} if absent, skipped untouched if present. The
 *       creation-time submodule import registers the qits fixture siblings and the clone ingests
 *       the committed {@code .qits-config.yml}.
 *   <li>For a {@code deepImport} entry, one further level of submodule import is applied over the
 *       freshly imported direct children — idempotent by the import's own semantics (dedup by url /
 *       {@code (parent, path)}), a no-op on childless siblings.
 * </ul>
 *
 * <p>Per-item matching also makes partial failure self-healing: a boot that created the project but
 * lost a clone to a network blip completes the missing pieces on the next boot, with no wedged
 * "already seeded" state. Each item is reconciled in its own try/catch, so one failing repository
 * never aborts the rest — the boot leaves a usable instance and the next reconcile retries exactly
 * the failed items.
 *
 * <p>The launch-mode gate (packaged runs only) and the off-startup-thread dispatch live in the
 * {@code service} module's startup bean; this service is the pure, testable reconcile logic. {@link
 * ActivateRequestContext} so the non-transactional reads ({@code list()}, {@code getRepositories},
 * {@code listSubmodules}) work when this runs on a worker thread with no ambient request context
 * (the cli seeds model the same pattern); the individual service calls still own their own
 * transactions.
 */
@ApplicationScoped
public class SelfSeedService {

  private static final Logger LOG = Logger.getLogger(SelfSeedService.class);

  static final String PROJECT_NAME = "qits";
  private static final String PROJECT_DESCRIPTION =
      "The qits repositories themselves, registered automatically at startup"
          + " (docs/epics/qits-live-deployment/features/2026-07-19_startup-qits-self-seed.md).";

  private static final String QITS_BACKEND_URL = "https://github.com/wohlben/qits-backend.git";
  private static final String QITS_ANGULAR_URL =
      "https://github.com/wohlben/qits-angular-integration.git";

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  /**
   * Redirects the {@code wohlben/qits-backend} clone source (mirror, fork, air-gapped file path).
   * Note per-item matching keys on the resolved clone url, so flipping this after a first seed
   * would register a second repository — acceptable for an escape hatch.
   */
  @ConfigProperty(name = "qits.startup-seed.repo-url")
  Optional<String> repoUrlOverride;

  /** Redirects the {@code wohlben/qits-angular-integration} clone source (same caveat as above). */
  @ConfigProperty(name = "qits.startup-seed.angular-integration-url")
  Optional<String> angularIntegrationUrlOverride;

  /**
   * A desired repository under the seeded project: its clone {@code url}, {@code archetype},
   * whether to import its direct submodules at creation, and whether to {@code deepImport} one
   * further level over those children (the automated equivalent of the registration guide's manual
   * second-level import).
   */
  public record SeedRepository(
      String url, RepositoryArchetype archetype, boolean importSubmodules, boolean deepImport) {}

  /**
   * The in-code manifest: both halves of qits. qits-backend imports its submodules (registering the
   * {@code testing-repo}/{@code qits-fixture-angular}/{@code testing-repo-quarkus-angular}
   * siblings) and deep-imports once (linking the quarkus-angular child's nested {@code webui}
   * gitlink back to the already-imported {@code qits-fixture-angular} sibling); the
   * {@code @qits/angular} library has no submodules. Url overrides (for mirrors/forks/air-gap and
   * tests) are applied here.
   */
  List<SeedRepository> manifest() {
    return List.of(
        new SeedRepository(
            resolveUrl(repoUrlOverride, QITS_BACKEND_URL), RepositoryArchetype.SERVICE, true, true),
        new SeedRepository(
            resolveUrl(angularIntegrationUrlOverride, QITS_ANGULAR_URL),
            RepositoryArchetype.SERVICE,
            false,
            false));
  }

  /**
   * The override url if set, else the default — <b>trimmed</b> to match how {@code cloneOne} stores
   * it ({@code url.trim()}). Without the trim a whitespace-padded override (a trailing newline in
   * an env file / k8s ConfigMap is common) would never re-match its own stored row, re-cloning a
   * duplicate repository on every boot.
   */
  private static String resolveUrl(Optional<String> override, String def) {
    return override.filter(s -> !s.isBlank()).orElse(def).trim();
  }

  /** Reconciles the manifest against the DB. Safe to run on every boot; additive and idempotent. */
  @ActivateRequestContext
  public void reconcile() {
    Project project = ensureProject();
    for (SeedRepository entry : manifest()) {
      try {
        reconcileRepository(project, entry);
      } catch (RuntimeException e) {
        // Non-fatal per item: log loudly and carry on, so one failing clone (a network blip on a
        // single repo) never denies the rest — the next boot's reconcile retries exactly this item.
        LOG.errorf(
            e, "Self-seed: failed to reconcile repository %s — retried on next boot.", entry.url());
      }
    }
  }

  /** The seeded project, created if absent and matched by name otherwise. */
  private Project ensureProject() {
    return projectService.list().stream()
        .filter(p -> PROJECT_NAME.equals(p.name))
        .findFirst()
        .orElseGet(
            () -> {
              LOG.infof("Self-seed: creating project '%s'.", PROJECT_NAME);
              return projectService.create(PROJECT_NAME, PROJECT_DESCRIPTION);
            });
  }

  private void reconcileRepository(Project project, SeedRepository entry) {
    Repository repo =
        projectService.getRepositories(project.id).stream()
            .filter(r -> entry.url().equals(r.url))
            .findFirst()
            .orElse(null);
    if (repo == null) {
      LOG.infof("Self-seed: registering repository %s under '%s'.", entry.url(), PROJECT_NAME);
      repo =
          projectService.createRepositoryUnderProject(
              project.id, entry.url(), entry.archetype(), entry.importSubmodules());
    } else {
      LOG.debugf("Self-seed: repository %s already present — left untouched.", entry.url());
    }

    if (entry.deepImport()) {
      deepImport(repo);
    }
  }

  /**
   * Descends one submodule level: for each direct child imported under {@code root}, imports that
   * child's own direct submodules. Override-independent (no path/url matching) and idempotent — a
   * no-op on childless siblings, and on the quarkus-angular child it links the nested {@code webui}
   * edge back to the already-imported {@code qits-fixture-angular} sibling.
   */
  private void deepImport(Repository root) {
    for (RepositorySubmodule edge : repositoryService.listSubmodules(root.id)) {
      repositoryService.importDirectSubmodules(edge.child.id);
    }
  }
}
