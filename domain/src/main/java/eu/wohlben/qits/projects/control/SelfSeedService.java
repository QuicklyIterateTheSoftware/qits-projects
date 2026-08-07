package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
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
 * <p>The seed is a two-line in-code {@linkplain #manifest() manifest} — the project plus the
 * wrapper repository and the pre-split monorepo — <b>reconciled on every boot</b>. It used to carry
 * a second, hand-maintained list of every platform repository the git host serves, one entry per
 * superproject submodule with its archetype spelled out beside it. <b>That list is gone.</b> The
 * superproject's own {@code .gitmodules} is that manifest, it is committed where the repositories
 * themselves live, and {@link WrapperReconcileService} reads it — so a repository joins qits by
 * being added to the wrapper, not by being added to a Java file that has to be deployed to take
 * effect. The old list's one virtue, "an entry with no origin here yet is skipped silently on every
 * boot", is preserved exactly: that is the reconcile's own adopt/clone/skip decision.
 *
 * <p><b>Idempotency is per item, not per seed</b>:
 *
 * <ul>
 *   <li>The project (named {@value #PROJECT_NAME}) is created if absent, matched by name otherwise.
 *   <li>The wrapper goes through the adopt seam and is then reconciled, which registers, adopts or
 *       re-archetypes every component it declares and deregisters every placeable row it does not.
 *   <li>The monorepo entry is matched by clone url within the project and created if absent. It is a
 *       {@code FORK}, which is unplaceable, so the reconcile leaves it alone.
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
 * ActivateRequestContext} so the non-transactional reads ({@code list()}, {@code getRepositories})
 * work when this runs on a worker thread with no ambient request context
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
  // REMOVED: the wohlben/qits-angular-integration entry. That upstream is the same repository the
  // platform's own git host now serves as `qits-integrations-angular` — provably so: the mirror's
  // head is a strict ancestor of the adopted origin's main. Adoption supersedes the clone, so the
  // entry is gone rather than repointed. Repointing is what P's note warns about: the clone manifest
  // matches on url, so a flipped url does not update the legacy row, it clones a SECOND one beside
  // it. Removing the entry is additive-safe in the other direction too — reconciliation never
  // deletes, so a deployment that still holds the legacy row keeps it until an operator says
  // otherwise. See platformManifest()'s `qits-integrations-angular`.

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  /** The wrapper's .gitmodules IS the repository manifest — see the class doc. */
  @Inject WrapperReconcileService wrapperReconcileService;

  /**
   * Redirects the {@code wohlben/qits-backend} clone source (mirror, fork, air-gapped file path).
   * Note per-item matching keys on the resolved clone url, so flipping this after a first seed
   * would register a second repository — acceptable for an escape hatch.
   */
  @ConfigProperty(name = "qits.startup-seed.repo-url")
  Optional<String> repoUrlOverride;

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

  /** A desired repository under the seeded project: its clone {@code url} and its archetype. */
  public record SeedRepository(String url, RepositoryArchetype archetype) {}

  /**
   * The in-code manifest: the upstreams this service must <b>clone</b> because nothing else holds
   * them. qits-backend imports its submodules (registering the {@code testing-repo}/{@code
   * qits-fixture-angular}/{@code testing-repo-quarkus-angular} siblings) and deep-imports once
   * (linking the quarkus-angular child's nested {@code webui} gitlink back to the already-imported
   * {@code qits-fixture-angular} sibling). Url overrides (for mirrors/forks/air-gap and tests) are
   * applied here.
   *
   * <p><b>This list shrinks as the platform onboards itself.</b> Every entry here is a {@code
   * wohlben/*} upstream from before the platform served its own git; when the platform's host starts
   * serving the same repository, {@link #platformManifest()} adopts it under its directory name and
   * the clone entry is <b>deleted from here</b> — the two would otherwise seed two rows for one
   * repository, indistinguishable in the ui and with only the adopted one carrying ci runs.
   * {@code qits-angular-integration} went that way; {@code qits-backend} has not, because it is the
   * pre-split monorepo and the parent of the fixture siblings, not a module the host serves.
   */
  List<SeedRepository> manifest() {
    return List.of(
        // The wrapper goes FIRST, and it is now the whole manifest: reconciling it registers every
        // component the superproject declares. Its upstream may be completely empty — adoption
        // seeds the project template skeleton on `main`, so nothing has to be pushed by hand first.
        new SeedRepository(
            resolveUrl(wrapperUrlOverride, QITS_WRAPPER_URL), RepositoryArchetype.PROJECT),
        // FORK, and deliberately so: qits-backend is the pre-split monorepo, not a component of
        // qits. An unplaceable archetype is exactly the honest answer for a repository that is kept
        // around and is not a submodule of the wrapper — it is exempt from membership and from the
        // reconcile's deregistration, and nothing has to pretend it belongs in a directory.
        new SeedRepository(resolveUrl(repoUrlOverride, QITS_BACKEND_URL), RepositoryArchetype.FORK));
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
        .orElseGet(this::createSeededProject);
  }

  /**
   * Creates the seeded project <b>with its wrapper upstream</b>, so the wrapper arrives carrying the
   * {@code .gitmodules} the reconcile then reads. Creating it greenfield and attaching the remote
   * afterwards would leave the wrapper holding the empty project template forever: attaching a
   * backup remote is a row write and fetches nothing.
   *
   * <p>An upstream that cannot be reached falls back to a greenfield wrapper rather than failing the
   * boot. The manifest's adopt step attaches the remote on this boot and every later one, so a
   * deployment that came up while the forge was down still has a usable instance — it simply has no
   * components until the wrapper can be read.
   */
  private Project createSeededProject() {
    String wrapperUrl = resolveUrl(wrapperUrlOverride, QITS_WRAPPER_URL);
    try {
      LOG.infof("Self-seed: creating project '%s' from wrapper %s.", PROJECT_NAME, wrapperUrl);
      return projectService.create(
          PROJECT_NAME, PROJECT_SLUG, PROJECT_DESCRIPTION, wrapperUrl, seededDnsRecord());
    } catch (RuntimeException e) {
      LOG.errorf(
          e,
          "Self-seed: could not create '%s' from wrapper %s — creating it greenfield; the next boot"
              + " retries the upstream.",
          PROJECT_NAME,
          wrapperUrl);
      return projectService.create(
          PROJECT_NAME, PROJECT_SLUG, PROJECT_DESCRIPTION, null, seededDnsRecord());
    }
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
    //
    // Then the wrapper's own .gitmodules is reconciled, and THAT is the seed's whole repository
    // list. Importing a wrapper url is restoring a project.
    if (entry.archetype() == RepositoryArchetype.PROJECT) {
      projectService.adoptWrapperRepository(project.id, entry.url());
      var reconciliation = wrapperReconcileService.reconcile(project.id);
      LOG.infof(
          "Self-seed: reconciled '%s' against its wrapper — %d entries.",
          PROJECT_NAME, reconciliation.entries().size());
      for (var outcome : reconciliation.entries()) {
        if (outcome.warning() != null) {
          LOG.warnf(
              "Self-seed: wrapper entry '%s' → %s: %s",
              outcome.path() == null ? outcome.name() : outcome.path(),
              outcome.outcome(),
              outcome.warning());
        }
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
      projectService.createRepositoryUnderProject(project.id, entry.url(), entry.archetype());
    } else {
      LOG.debugf("Self-seed: repository %s already present — left untouched.", entry.url());
    }
  }
}
