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
import java.util.Set;
import java.util.stream.Collectors;
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
 *   <li>Each {@linkplain #platformManifest() platform repository} the platform's own git host
 *       already serves is <b>adopted</b> under the same project, matched by id, skipped when its
 *       origin is not on the volume.
 * </ul>
 *
 * <p><b>There are two manifests because there are two ways a qits repository comes to exist.</b>
 * The one above clones an upstream this service does not otherwise hold. The platform one registers
 * origins that are already on the shared volume — the bootstrap initializes them there directly,
 * and they are pushed to, built and deployed with no row here at all — so it adopts them under the
 * directory name they are served under, which is the id everything downstream (a {@code
 * CiRun.repoId}, a cd application) already carries. See {@link
 * RepositoryService#adoptExistingOrigin}.
 *
 * <p><b>A repository belongs to exactly one of them.</b> When the platform's host begins serving an
 * upstream the clone manifest used to fetch, the clone entry is removed and adoption takes over —
 * leaving it in both would seed two rows for one repository, and only the adopted row is the one
 * ci, cd and the git host key on. Because reconciliation never deletes, removing a clone entry
 * stops the row being <i>created</i> but does not retire one already seeded; that is an operator's
 * deletion to make, and the {@code wohlben/qits-angular-integration} row was retired that way.
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

  /**
   * A desired repository under the seeded project: its clone {@code url}, {@code archetype},
   * whether to import its direct submodules at creation, and whether to {@code deepImport} one
   * further level over those children (the automated equivalent of the registration guide's manual
   * second-level import).
   */
  public record SeedRepository(
      String url, RepositoryArchetype archetype, boolean importSubmodules, boolean deepImport) {}

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
        // The wrapper goes FIRST: it is the project root and, once extraction starts, the
        // superproject of the others. Its upstream may be completely empty — adoption seeds the
        // project template skeleton on `main`, so nothing has to be pushed by hand first.
        new SeedRepository(
            resolveUrl(wrapperUrlOverride, QITS_WRAPPER_URL),
            RepositoryArchetype.PROJECT,
            true,
            false),
        new SeedRepository(
            resolveUrl(repoUrlOverride, QITS_BACKEND_URL), RepositoryArchetype.SERVICE, true, true));
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

  /**
   * The forge namespace the platform's own repositories live in. Every {@link PlatformRepository}
   * url is this plus the id plus {@code .git} — the same thing the superproject's {@code
   * .gitmodules} records for that entry, which is what makes the mapping checkable rather than
   * asserted.
   */
  private static final String PLATFORM_FORGE = "https://github.com/QuicklyIterateTheSoftware/";

  /**
   * One of the platform's own repositories, already served by the platform's own git host: the
   * shared-volume directory name it is served under — <b>which becomes {@code Repository.id}</b> —
   * and what kind of part of qits it is.
   *
   * <p>No url field: it is derived from the id against {@link #PLATFORM_FORGE}, because for these
   * repositories the two are the same fact spelled twice.
   */
  public record PlatformRepository(String id, RepositoryArchetype archetype) {
    public String url() {
      return PLATFORM_FORGE + id + ".git";
    }
  }

  /**
   * The platform's own repositories — the superproject's submodule list, one entry per module,
   * archetype taken from the directory it is mounted under (which is exactly what {@link
   * RepositoryArchetype#directory()} declares in the other direction: {@code services} → {@link
   * RepositoryArchetype#SERVICE SERVICE}, {@code libs} → {@link RepositoryArchetype#LIBRARY
   * LIBRARY}, {@code integrations} → {@link RepositoryArchetype#INTEGRATION INTEGRATION}). The two
   * roles the skeleton has no directory for map by their own semantics: a {@code frontends/} entry
   * is a thing served to a user at a URL, so {@link RepositoryArchetype#APPLICATION APPLICATION},
   * and a {@code daemons/} entry is a deployed component, so {@code SERVICE}.
   *
   * <p>Listing a repository the git host does not carry is <b>not</b> an error and not a
   * prediction: an entry with no origin on the volume is skipped, silently, on every boot until the
   * day that origin appears. That is what makes the manifest the superproject's module list rather
   * than a snapshot of one deployment — the bootstrap seeds the nine deployables, this host has
   * gained four more by hand since, and both onboard themselves with no edit here.
   *
   * <p>The wrapper ({@code qits-qits}) is deliberately absent: it is the project's {@link
   * RepositoryArchetype#PROJECT} root, {@code adoptWrapperRepository} is the only seam that may
   * mint one, and {@link #manifest()} above already owns it.
   */
  List<PlatformRepository> platformManifest() {
    return List.of(
        // services/ — deployable components.
        new PlatformRepository("qits-gateway", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-artifacts", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-observability", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-workspaces", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-projects", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-repositories", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-events", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-ci", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-cd", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-idp", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-dns", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-stt", RepositoryArchetype.SERVICE),
        // daemons/ — long-running agents, deployed rather than served.
        new PlatformRepository("qits-ci-daemon", RepositoryArchetype.SERVICE),
        new PlatformRepository("qits-workspace-daemon", RepositoryArchetype.SERVICE),
        // libs/ — shared code consumed by the others.
        new PlatformRepository("qits-userflows", RepositoryArchetype.LIBRARY),
        new PlatformRepository("qits-eventstream", RepositoryArchetype.LIBRARY),
        new PlatformRepository("qits-spa-ui-components", RepositoryArchetype.LIBRARY),
        // integrations/ — framework-specific glue.
        new PlatformRepository("qits-integrations-angular", RepositoryArchetype.INTEGRATION),
        new PlatformRepository("qits-integrations-quarkus", RepositoryArchetype.INTEGRATION),
        // frontends/ — one entry per thing served at a URL.
        new PlatformRepository("qits-spa-home", RepositoryArchetype.APPLICATION),
        new PlatformRepository("qits-spa-projects", RepositoryArchetype.APPLICATION),
        new PlatformRepository("qits-spa-workspaces", RepositoryArchetype.APPLICATION),
        new PlatformRepository("qits-spa-artifacts", RepositoryArchetype.APPLICATION),
        new PlatformRepository("qits-spa-observability", RepositoryArchetype.APPLICATION),
        new PlatformRepository("qits-spa-events", RepositoryArchetype.APPLICATION),
        new PlatformRepository("qits-spa-ci", RepositoryArchetype.APPLICATION),
        new PlatformRepository("qits-spa-cd", RepositoryArchetype.APPLICATION),
        // images/ — build definitions consumed through their published OCI images.
        new PlatformRepository("qits-oci", RepositoryArchetype.LIBRARY));
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
    reconcilePlatformRepositories(project);
  }

  /**
   * Registers the platform's own repositories — the ones the platform's git host already serves —
   * under the seeded project, each keyed by the directory name it is served under.
   *
   * <p>This is the half of the seed that <b>adopts rather than clones</b>, and it exists because
   * the platform's repositories reached the git host without ever passing through this service:
   * the bootstrap initializes their bare origins on the shared volume directly, so they accumulate
   * pushes, ci runs and deployments while no {@code Repository} row names them. Every one of those
   * facts is keyed on the directory name, which is why {@link
   * RepositoryService#adoptExistingOrigin} takes the id rather than minting one — a UUID row would
   * be attached to nothing.
   *
   * <p>Additive and per-item, exactly like the clone manifest above: an entry with no origin on the
   * volume is skipped, an id already carrying a row is left untouched, and one failing entry never
   * denies the rest.
   */
  private void reconcilePlatformRepositories(Project project) {
    Set<String> known =
        projectService.getRepositories(project.id).stream()
            .map(r -> r.id)
            .collect(Collectors.toSet());
    for (PlatformRepository entry : platformManifest()) {
      try {
        if (!repositoryService.hasExistingOrigin(entry.id())) {
          LOG.debugf(
              "Self-seed: no origin on the git host for %s — nothing to adopt yet.", entry.id());
          continue;
        }
        repositoryService.adoptExistingOrigin(project, entry.id(), entry.url(), entry.archetype());
        if (!known.contains(entry.id())) {
          LOG.infof(
              "Self-seed: adopted platform repository %s (%s) under '%s'.",
              entry.id(), entry.archetype(), PROJECT_NAME);
        }
      } catch (RuntimeException e) {
        LOG.errorf(
            e,
            "Self-seed: failed to adopt platform repository %s — retried on next boot.",
            entry.id());
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
