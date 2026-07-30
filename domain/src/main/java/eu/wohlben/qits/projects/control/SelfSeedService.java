package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositorySubmodule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reconciles a packaged qits deployment into a seeded "qits" project holding the qits repositories
 * themselves — the startup counterpart to the cli {@code seed}/{@code seed-webapp} demos (see
 * {@code docs/epics/qits-live-deployment/features/2026-07-19_startup-qits-self-seed.md}). Where
 * those seeds define a demo world programmatically, this one only registers the real repositories.
 * Their committed {@code .qits-config.yml} (services, actions, bootstrap chain) is the
 * workspace-scoped source of truth, read <b>in-container per workspace</b> by the workspace-daemon
 * — nothing is ingested into DB rows on clone (the repo-scoped config store was removed in Part 5,
 * {@code
 * docs/epics/qits-workspace-daemon/features/2026-07-24_config-as-single-source-of-truth.md}).
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
 *       creation-time submodule import registers the qits fixture siblings.
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

  /**
   * Passed explicitly rather than left to {@code slugify(PROJECT_NAME)}: the wrapper's adopt check
   * (basename of the manifest url must equal {@code <slug>-<slug>}) hangs off this value, so it is
   * load-bearing and must not drift with the display name.
   */
  static final String PROJECT_SLUG = "qits";

  private static final String PROJECT_DESCRIPTION =
      "The qits repositories themselves, registered automatically at startup"
          + " (docs/epics/qits-live-deployment/features/2026-07-19_startup-qits-self-seed.md).";

  private static final String QITS_WRAPPER_URL = "https://github.com/wohlben/qits-qits.git";
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
   * Redirects the {@code wohlben/qits-qits} wrapper clone source (same caveat as above, plus one
   * more: an override whose basename is not {@code qits-qits} fails the wrapper name validation, so
   * a fixture it points at must be named accordingly).
   */
  @ConfigProperty(name = "qits.startup-seed.wrapper-url")
  Optional<String> wrapperUrlOverride;

  /**
   * The fqdn the seeded project resolves through, e.g. {@code qits.eu}.
   *
   * <p><b>All three of {@code dns-domain} / {@code dns-type} / {@code dns-value} together or none
   * of them.</b> Absent — the shipped default — means the seeded project is created with no domain
   * and registers nothing, which is exactly what the nullable columns exist for; a deployment that
   * owns a name sets three env vars and the reconcile does the rest. Set partially, they are
   * treated as absent and said so once in the log, because a half-configured record is a typo
   * rather than an intent and the seed is not a place to fail a boot over one.
   */
  @ConfigProperty(name = "qits.startup-seed.dns-domain")
  Optional<String> dnsDomain;

  /** The record type: {@code A}, {@code AAAA} or {@code CNAME}. See {@link #dnsDomain}. */
  @ConfigProperty(name = "qits.startup-seed.dns-type")
  Optional<String> dnsType;

  /** The address or CNAME target. See {@link #dnsDomain}. */
  @ConfigProperty(name = "qits.startup-seed.dns-value")
  Optional<String> dnsValue;

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
        // The wrapper goes FIRST: it is the project root and, once extraction starts, the
        // superproject of the others. Its upstream may be completely empty — adoption seeds the
        // project template skeleton on `main`, so nothing has to be pushed by hand first.
        new SeedRepository(
            resolveUrl(wrapperUrlOverride, QITS_WRAPPER_URL),
            RepositoryArchetype.PROJECT,
            true,
            false),
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

  /**
   * The seeded project, created if absent and matched by name otherwise.
   *
   * <p>Note the asymmetry this leaves, accepted deliberately (main-environment-plan.md §3): on an
   * already-seeded deployment the project is found rather than created, so the creation hooks do
   * not fire for it and its environment and domain are not reconciled. One curl closes that per
   * project; teaching the reconcile to re-fire them would mean making the hooks themselves
   * reconciliation-aware, which is more machinery than a placeholder model deserves.
   */
  private Project ensureProject() {
    return projectService.list().stream()
        .filter(p -> PROJECT_NAME.equals(p.name))
        .findFirst()
        .orElseGet(
            () -> {
              LOG.infof("Self-seed: creating project '%s'.", PROJECT_NAME);
              return projectService.create(
                  PROJECT_NAME, PROJECT_SLUG, PROJECT_DESCRIPTION, null, seededDnsRecord());
            });
  }

  /**
   * The configured dns record, or {@code null} when the three keys are not all set — see {@link
   * #dnsDomain}.
   *
   * <p>An unparseable {@code dns-type} is warned about and read as "no domain" rather than thrown:
   * {@code ensureProject} is outside {@code reconcile}'s per-item try/catch, so a typo in one env
   * var would otherwise take the whole seed — and with it every repository registration — down with
   * it. The format of the other two is {@code ProjectService.create}'s to reject, and it does so
   * loudly on the same path.
   */
  ProjectDnsRecord seededDnsRecord() {
    String domain = trimmedOrNull(dnsDomain);
    String type = trimmedOrNull(dnsType);
    String value = trimmedOrNull(dnsValue);
    if (domain == null && type == null && value == null) {
      return null; // the shipped default: the seeded project registers no domain.
    }
    if (domain == null || type == null || value == null) {
      LOG.warnf(
          "Self-seed: qits.startup-seed.dns-domain/-type/-value must all be set or all be unset"
              + " (domain=%s, type=%s, value=%s) — the seeded project registers no domain.",
          domain, type, value);
      return null;
    }
    ProjectDnsRecordType parsed;
    try {
      parsed = ProjectDnsRecordType.valueOf(type.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          "Self-seed: qits.startup-seed.dns-type '%s' is not one of A, AAAA, CNAME — the seeded"
              + " project registers no domain.",
          type);
      return null;
    }
    return new ProjectDnsRecord(domain, parsed, value);
  }

  /**
   * An override's value with surrounding whitespace gone, or null when it carries none — see {@link
   * #resolveUrl} for why a trailing newline is the case worth handling.
   */
  private static String trimmedOrNull(Optional<String> configured) {
    return configured.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
  }

  private void reconcileRepository(Project project, SeedRepository entry) {
    // The wrapper goes through the adopt seam, never createRepositoryUnderProject (which rejects
    // PROJECT outright). adoptWrapperRepository is idempotent across all the states a reconcile can
    // find: it creates the wrapper, promotes a repository already registered at that url, attaches
    // the remote to the greenfield wrapper ensureProject just made, or no-ops once adopted.
    if (entry.archetype() == RepositoryArchetype.PROJECT) {
      Repository wrapper = projectService.adoptWrapperRepository(project.id, entry.url());
      if (entry.importSubmodules()) {
        repositoryService.importDirectSubmodules(wrapper.id);
      }
      if (entry.deepImport()) {
        deepImport(wrapper);
      }
      return;
    }

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
