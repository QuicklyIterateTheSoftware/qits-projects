package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.validation.DnsFqdn;
import eu.wohlben.qits.projects.validation.DnsFqdnValidator;
import eu.wohlben.qits.projects.validation.DnsRecordValueValidator;
import eu.wohlben.qits.projects.validation.ProjectSlugValidator;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ProjectService {

  private static final Logger LOG = Logger.getLogger(ProjectService.class);

  /**
   * The <b>derivation</b> cap. A slug names the wrapper repository {@code <slug>-<slug>}, and that
   * name is the wrapper's id — a git-host path segment of at most 64 characters — so a derived slug
   * must fit twice plus the joining dash: 31+1+31 = 63. {@code ProjectSlug.PATTERN} still accepts
   * an explicitly supplied slug of up to 40; a longer one is a statement, and wrapper creation
   * refuses it loudly when {@code <slug>-<slug>} cannot be an id.
   */
  private static final int MAX_SLUG_LENGTH = 31;

  /**
   * The slugs no project may take, because every service host reads them as something else.
   *
   * <p>A slug is the <b>first path segment</b> of every address on every SPA —
   * {@code https://ci.dev.example.com/<slug>/services/qits-ci/runs} — and each of those hosts also
   * path-routes every application's own segment and every wire route, on every host. So a project
   * whose slug spells one of them would be shadowed by the route: {@code /projects/…} reaches this
   * service's API rather than the project called "projects", and nothing would say so.
   *
   * <p>Three families, one list because they are one rule:
   *
   * <ul>
   *   <li>the six repository categories and the {@code components} marker — segment two of the
   *       repository form, so a slug spelling one would make {@code /services/daemons/x}
   *       unreadable. The six stay reserved while the wrapper flip is in progress; {@code
   *       components} joins them because it is what segment two becomes;
   *   <li>{@code api}, {@code q} and {@code main-navigation} — served under every host;
   *   <li>every application segment the platform routes.
   * </ul>
   *
   * <p><b>A new service segment belongs here on the day it is routed</b>, before a project can take
   * it — see {@code docs/project-setup-quinoa-angular.md} in the superproject.
   */
  private static final Set<String> RESERVED_SLUGS =
      Set.of(
          "services",
          "daemons",
          "libs",
          "frontends",
          "cli",
          "images",
          "components",
          "api",
          "q",
          "main-navigation",
          "projects",
          "ci",
          "workspaces",
          "artifacts",
          "docs",
          "configuration",
          "observability",
          "githost",
          "git",
          "v2",
          "events",
          "platform-deployments",
          "maintenance",
          "mirror",
          "orchestrator",
          "system",
          "idp",
          "stt");

  @Inject ProjectRepository projectRepository;

  @Inject RepositoryRepository repositoryRepository;

  @Inject RepositoryNameRepository repositoryNameRepository;

  @Inject RepositoryService repositoryService;

  @Inject WrapperSubmoduleWriter wrapperSubmoduleWriter;

  // Fired by #announce after a creation commits. Optional, like every port here — see the
  // interface's javadoc for what absent means and why it is a supported configuration.
  // NOTE: this is the hook that would register a dns record. Nothing implements the port since
  // qits-platform-dns was removed, so the loop in #announce runs zero times and dns is configured
  // by hand at the external provider.
  @Inject Instance<ProjectDomainRegistrar> domainRegistrars;

  /**
   * Creates a project with its slug <b>derived</b> from {@code name} (see {@link #slugify}) — the
   * convenience form for callers that have no slug of their own to give (the cli seeds, tests).
   *
   * <p>Prefer {@link #create(String, String, String)} wherever the slug is load-bearing: a derived
   * slug is only as stable as the display name it came from.
   */
  public Project create(String name, String description) {
    return create(name, null, description);
  }

  /** Creates a project with no wrapper upstream — the wrapper is initialized locally. */
  public Project create(String name, String slug, String description) {
    return create(name, slug, description, null);
  }

  /**
   * Creates a project that registers no domain — see {@link #create(String, String, String, String,
   * ProjectDnsRecord)} for the full form.
   */
  public Project create(String name, String slug, String description, String wrapperUrl) {
    return create(name, slug, description, wrapperUrl, null);
  }

  /**
   * Creates a project and, as the <b>last step</b>, its {@linkplain
   * eu.wohlben.qits.projects.entity.RepositoryArchetype#PROJECT wrapper repository} — so project
   * creation always ends with one repository, no matter what.
   *
   * <p>The wrapper is named {@code <slug>-<slug>}: a repository's name is a project-scoped alias
   * served at {@code /git/<projectId>/<name>}, and a committed relative submodule url ({@code
   * ../<name>.git}) folds against the superproject's <em>real backend</em> — so for the two to
   * agree a repository's local alias must equal its remote basename. Forge namespaces are flat,
   * which makes the established convention {@code <project>-<component>}; the wrapper's "component"
   * is the project itself. The name is <b>derived, never supplied</b> — derivation is the
   * enforcement.
   *
   * <p><b>Every overload lands here, and so every creation announces itself</b> ({@link #announce})
   * — the REST controller, the self-seed and anything added later get the project's domain
   * registration without knowing the port exists. That is the whole reason the hook hangs off the
   * service rather than off the controller.
   *
   * <p><b>Not {@code @Transactional}.</b> The row and its wrapper are committed by an explicit
   * {@link QuarkusTransaction#requiringNew()} block and the port is called <em>after</em> it, so an
   * implementation that reads the project back finds it — the arrangement {@code
   * CiRunService.notifyCd} uses for the same reason. An interceptor on this method would put the
   * announcement inside the transaction it is meant to follow, and a self-invoked
   * {@code @Transactional} helper would not be intercepted at all.
   *
   * @param slug the git-safe project identity, or {@code null} to derive it from {@code name}
   * @param wrapperUrl an existing upstream to adopt as the wrapper (brownfield), or {@code null} to
   *     initialize a remote-less one locally (greenfield). An adopted upstream may be completely
   *     empty — it is seeded with the project template skeleton — but its basename must equal
   *     {@code <slug>-<slug>}.
   * @param dns the domain this project resolves through, or {@code null} to register none. Required
   *     at the API; nullable here because the self-seed is also a caller and may run with no domain
   *     configured.
   */
  public Project create(
      String name, String slug, String description, String wrapperUrl, ProjectDnsRecord dns) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("name is required");
    }
    // Ahead of the transaction: a rejected record must leave nothing behind, and nothing below this
    // line needs a database to decide it.
    validateDns(dns);

    Project project =
        QuarkusTransaction.requiringNew()
            .call(() -> persistProject(name, slug, description, wrapperUrl, dns));

    announce(project);
    return project;
  }

  /** The transactional half of {@link #create}: the row, then the wrapper repository. */
  private Project persistProject(
      String name, String slug, String description, String wrapperUrl, ProjectDnsRecord dns) {
    Project project = new Project();
    project.id = UUID.randomUUID().toString();
    project.name = name;
    project.slug = resolveSlug(name, slug, project.id);
    project.description = description;
    project.dns = dns;
    projectRepository.persist(project);

    createWrapperRepository(project, wrapperUrl);
    return project;
  }

  /**
   * Tells the creation port the project exists — its domain.
   *
   * <p>Called after the creating transaction commits, so an implementation that reads the project
   * back sees it. <b>Every failure is swallowed</b>: a project must never fail to exist because a
   * sibling service was down, and a caller who gets a 500 from a creation that in fact succeeded is
   * worse off than one whose record appears a boot later. An absent implementation is a supported
   * configuration.
   *
   * <p>The registrar is skipped, silently, for a project with no record — that is the documented
   * "no domain" state, not a failure to configure one.
   *
   * <p>A project no longer announces a deployment environment. qits-cd owns environments now: they
   * are deliberate tiers created over its own REST surface, not one per project.
   */
  private void announce(Project project) {
    if (project.dns == null) {
      return;
    }
    for (ProjectDomainRegistrar registrar : domainRegistrars) {
      try {
        registrar.register(
            project.id, project.slug, project.dns.domain, project.dns.type, project.dns.value);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Domain registration for project %s failed", project.id);
      }
    }
  }

  /**
   * Re-asserts the dns record's format in the domain layer.
   *
   * <p>The Bean Validation constraints on the request DTO only guard HTTP; the self-seed reads
   * three config keys and reaches {@code create} without passing through them, so this is the
   * enforcement that actually holds — the same reasoning, and the same shape, as {@link
   * #resolveSlug}.
   *
   * <p>A {@code null} record is valid (no domain). A record with any field missing is not: a
   * half-filled embeddable would be indistinguishable from "no domain" in some columns and not
   * others, and the null-embeddable read this model depends on would stop meaning one thing.
   */
  private static void validateDns(ProjectDnsRecord dns) {
    if (dns == null) {
      return;
    }
    if (!DnsFqdnValidator.matches(dns.domain)) {
      throw new BadRequestException(
          "Invalid dns domain '"
              + dns.domain
              + "': must be a lowercase fully-qualified name of at least two dot-separated dns"
              + " labels (letters, digits and inner hyphens, no leading or trailing hyphen), at"
              + " most "
              + DnsFqdn.MAX_LENGTH
              + " characters. It becomes what an authoritative nameserver answers, so a second"
              + " spelling of one hostname is not accepted.");
    }
    if (dns.type == null) {
      throw new BadRequestException("dns type is required (one of A, AAAA, CNAME)");
    }
    if (!DnsRecordValueValidator.matches(dns.value)) {
      throw new BadRequestException(
          "Invalid dns value for "
              + dns.type
              + " record '"
              + dns.domain
              + "': a value is required for every type — a CNAME with no target is not a record —"
              + " and must carry no whitespace or control characters.");
    }
  }

  /**
   * Validates an explicitly supplied slug, or derives one from the project name — and makes it
   * unique either way, but by two different means on purpose.
   *
   * <p>The Bean Validation constraint on the request DTO only guards HTTP; the self-seed, both cli
   * seeds and MCP all reach {@code create} without passing through it, so the format is re-asserted
   * here — this is the enforcement that actually holds.
   *
   * <p><b>A derived slug takes the next free suffix</b> ({@code -2}, {@code -3}, …), the way an
   * epic's does within its project. The caller gave a display name and no slug, so it has stated
   * nothing about the value and two projects called "Checkout" must both be creatable.
   *
   * <p><b>A supplied slug that is taken is a 409.</b> It is a statement rather than a default: it
   * names the project's upstream backup organisation and its wrapper repository ({@code
   * <slug>-<slug>}), so a silent rename would create a project whose wrapper does not match the
   * upstream the caller meant, and nothing would say so until a push failed.
   *
   * <p><b>A {@linkplain #RESERVED_SLUGS reserved} slug is a 400 either way.</b> Supplied, it is
   * refused loudly, because the caller named a value the platform cannot address. Derived, it takes
   * the next free suffix like any other collision — the name says nothing about the slug, so
   * "Docs" becoming {@code docs-2} is the same answer a second project called "Docs" would get.
   */
  private String resolveSlug(String name, String slug, String projectId) {
    if (slug == null || slug.isBlank()) {
      return nextFreeSlug(slugify(name, projectId));
    }
    String trimmed = slug.trim();
    if (RESERVED_SLUGS.contains(trimmed)) {
      throw new BadRequestException(
          "The slug '"
              + trimmed
              + "' is reserved. A slug is the first path segment of every address on every"
              + " application host, and that segment already routes something else — a repository"
              + " category, a platform path, or an application's own segment. Choose another.");
    }
    if (!ProjectSlugValidator.matches(trimmed)) {
      throw new BadRequestException(
          "Invalid project slug '"
              + trimmed
              + "': must be 1-40 characters of lowercase letters, digits and inner dashes (no"
              + " leading or trailing dash). It becomes a git path segment and a forge repository"
              + " name, so it must survive both unchanged.");
    }
    if (projectRepository.findBySlug(trimmed).isPresent()) {
      throw new DomainException(
          409,
          "The slug '"
              + trimmed
              + "' already names another project. A slug is unique: it names this project's upstream"
              + " backup organisation and its wrapper repository ('"
              + trimmed
              + "-"
              + trimmed
              + "'), so two projects cannot share one. Choose another, or omit the field to have one"
              + " derived from the name.");
    }
    return trimmed;
  }

  /**
   * {@code base}, or the first free {@code -2}, {@code -3}, … — the same rule epics' {@code
   * Slugs.unique} applies within its scope, whose scope here is the whole service.
   *
   * <p>A read before a write with no lock, so two concurrent creates of the same name can both pass
   * it and the second then fails the unique constraint as a 500. Accepted, as it is in epics:
   * project creation is hand-driven and a retry succeeds.
   *
   * <p>A {@linkplain #RESERVED_SLUGS reserved} slug counts as taken, so a project called "Docs"
   * derives {@code docs-2} rather than failing. The suffixing is what keeps the reservation from
   * making an ordinary display name uncreatable.
   */
  private String nextFreeSlug(String base) {
    if (isFree(base)) {
      return base;
    }
    for (int n = 2; ; n++) {
      String suffix = "-" + n;
      String head =
          base.length() + suffix.length() <= MAX_SLUG_LENGTH
              ? base
              : base.substring(0, MAX_SLUG_LENGTH - suffix.length()).replaceAll("-+$", "");
      String candidate = head + suffix;
      if (isFree(candidate)) {
        return candidate;
      }
    }
  }

  /** Neither reserved by the platform's routing nor already another project's. */
  private boolean isFree(String slug) {
    return !RESERVED_SLUGS.contains(slug) && projectRepository.findBySlug(slug).isEmpty();
  }

  /**
   * Derives a git-safe slug from a display name: lowercase, every run of non-alphanumerics becomes
   * a dash, leading/trailing dashes stripped, capped at {@link #MAX_SLUG_LENGTH} characters so the
   * wrapper name {@code <slug>-<slug>} always fits a repository id.
   *
   * <p><b>Total by construction</b> — the result always satisfies {@code ProjectSlug.PATTERN}. A
   * name with nothing alphanumeric in it ({@code "***"}, a pure-unicode name) would slugify to the
   * empty string, so it falls back to the project id's prefix, which is UUID hex and therefore
   * always valid. V44's backfill mirrors this exactly in SQL.
   */
  public static String slugify(String name, String projectId) {
    String slug =
        (name == null ? "" : name)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
    if (slug.length() > MAX_SLUG_LENGTH) {
      // The cut can land on a dash, which a trailing dash is not allowed to be.
      slug = slug.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");
    }
    if (slug.isEmpty()) {
      return "project-"
          + projectId.substring(0, Math.min(8, projectId.length())).toLowerCase(Locale.ROOT);
    }
    return slug;
  }

  /** The name a project's wrapper repository is addressable by: {@code <slug>-<slug>}. */
  public static String wrapperName(Project project) {
    return project.slug + "-" + project.slug;
  }

  /** The project's wrapper repository, if it has one. Projects predating the feature have none. */
  public Optional<Repository> findWrapper(String projectId) {
    return repositoryRepository.findWrapperByProject(projectId);
  }

  public Project get(String id) {
    return projectRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Project not found: " + id));
  }

  public List<Project> list() {
    return projectRepository.listAll();
  }

  /**
   * Renames or re-describes a project — this service's one write with nothing but rows in it, and
   * so the one that can be <b>held through a postgres cutover</b> ({@link DbRetry#inNewTx}).
   *
   * <p>{@code inNewTx} owns the transaction, retrying only an attempt that certainly did not commit
   * — the body threw a connection-class failure, which Quarkus rolls back and never commits. That
   * replaces {@code @Transactional} rather than sitting outside it: a joined transaction is not one
   * the retry could open again. The caller, {@code ProjectController.update}, opens none.
   *
   * <p><b>The flush is what makes the retry reach this write.</b> Hibernate flushes a dirty entity
   * at commit, which would put the UPDATE in the commit phase — the one round trip nobody can place,
   * and the one {@code inNewTx} reports rather than repeats. Flushing last moves it into the
   * statement phase, where a severed connection is a certain no-commit.
   *
   * <p>Every other write in this service reaches out of the database inside its transaction — a
   * clone, an HTTP call to the git host, a workspace teardown, a mirror directory removed — and a
   * retry would do those a second time. They are deliberately left alone; see {@link #delete} and
   * {@link #createRepositoryUnderProject}.
   */
  public Project update(String id, String name, String description) {
    return DbRetry.inNewTx(
        "project update",
        () -> {
          Project project = get(id);

          if (name != null && !name.isBlank()) {
            project.name = name;
          }
          if (description != null) {
            project.description = description;
          }

          projectRepository.getEntityManager().flush();
          return project;
        });
  }

  /**
   * Deletes the project and every repository under it, wrapper included — and because each one goes
   * through {@link RepositoryService#deleteInternal}, every one of those repositories is deleted on
   * the git host too, history and all.
   */
  @Transactional
  public void delete(String id) {
    Project project = get(id);
    // SEAM (migration-plan.md §6, project <-> featureflow): the monorepo deleted this project's
    // flow configurations first, because their phase actions bind repository-scoped actions over an
    // FK with no cascade. domain.featureflow is monolith-only and deferred (§9 item 6), so neither
    // the entity nor its table exists in this context's database and there is nothing to delete
    // ahead of the repositories.
    // Delegate to RepositoryService.delete (not a raw row delete) so each repository's containers
    // and on-disk clone are torn down too — otherwise deleting a project (e.g. a seed reset) leaks
    // them as orphans.
    // deleteInternal, not delete: the wrapper refuses a standalone delete (it is the project root),
    // but it must go with the project it is the root of.
    repositoryRepository.find("project.id", id).list().stream()
        .map(r -> r.id)
        .forEach(repositoryService::deleteInternal);
    projectRepository.delete(project);
  }

  public List<Repository> getRepositories(String projectId) {
    get(projectId); // verify project exists
    return repositoryRepository.find("project.id", projectId).list();
  }

  /**
   * Clones an existing repository under {@code project} and <b>does not touch the wrapper</b> — the
   * primitive, and the right call for a repository that is deliberately not a component of the
   * project (an unplaceable {@code FORK}, the self-seed's monorepo entry).
   */
  @Transactional
  public Repository createRepositoryUnderProject(
      String projectId, String url, RepositoryArchetype archetype) {
    Project project = get(projectId);
    // The wrapper is never created through the ordinary repositories path: it is derived from the
    // project's slug and owned by adoptWrapperRepository, which is the single seam that may mint or
    // promote one. Allowing it here would let a second wrapper in past the guard.
    if (archetype == RepositoryArchetype.PROJECT) {
      throw new BadRequestException(
          "A repository cannot be created with archetype PROJECT: that archetype is reserved for"
              + " the project's wrapper repository, which is created with the project.");
    }

    return repositoryService.cloneRepository(url, archetype, project);
  }

  /**
   * A repository and the wrapper entry that makes it part of the project.
   *
   * @param wrapperPath where the wrapper mounts it, e.g. {@code services/checkout}
   */
  public record CreatedRepository(Repository repository, String wrapperPath) {}

  /**
   * The create flow: one of two ways to get a repository, then one way to make it a component.
   *
   * <ul>
   *   <li><b>{@code name}</b> — a blank repository on the platform's own git host, seeded with the
   *       repository template. Nothing external is involved and the name is exactly what {@code
   *       ../<name>.git} will resolve to.
   *   <li><b>{@code url}</b> — an existing external repository, mirrored in and published to the
   *       host. Its submodules are not followed: the wrapper says what belongs to the project.
   * </ul>
   *
   * <p>Exactly one of the two, because they are different intentions and a request carrying both
   * says neither. The archetype must be placeable — an unplaceable one has no directory to be
   * mounted under, and a component that is not in the wrapper is not a component.
   *
   * <p><b>The wrapper commit runs last and outside the row's transaction.</b> It is a push to
   * another service and can fail on its own; when it does, the request fails loudly with the row
   * already created, retrying is idempotent (the entry is added once), and a reconcile heals a
   * straggler nobody retried. The alternative — holding a database transaction open across a network
   * write — trades a visible failure for an invisible one.
   */
  public CreatedRepository createRepository(
      String projectId, String url, String name, RepositoryArchetype archetype) {
    return createRepository(projectId, url, name, archetype, null);
  }

  /**
   * The create flow, with the component the caller wants the entry mounted under — see {@link
   * #createRepository(String, String, String, RepositoryArchetype)} for everything else.
   *
   * <p>{@code component} is optional and the wrapper still has the last word on the placement
   * ({@link WrapperSubmoduleWriter#addToWrapper}): stating one places under {@code
   * components/<component>/<name>}, and stating none lets a wrapper that has already flipped place
   * there anyway. The row's {@code component} is then read back off the path the wrapper commit
   * actually used, so the row and the file cannot disagree about it.
   *
   * <p><b>{@code archetype} is optional too, and the name is what fills it in.</b> Under the
   * component layout the name is what says the kind, so {@code payments-daemon} needs no second
   * statement of it ({@link RepositoryArchetype#fromRepositoryName}) — and for the attach arm the
   * name derived is the url's basename, which is exactly the name the repository will answer to. A
   * caller that states one is obeyed unchanged, which is what keeps the SPA's create form working.
   * A request that states neither an archetype nor a name carrying a role suffix is refused, because
   * guessing a kind is the one thing nothing downstream could correct.
   */
  public CreatedRepository createRepository(
      String projectId, String url, String name, RepositoryArchetype archetype, String component) {
    Project project = get(projectId);
    String trimmedUrl = url == null || url.isBlank() ? null : url.trim();
    String trimmedName = name == null || name.isBlank() ? null : name.trim();
    if ((trimmedUrl == null) == (trimmedName == null)) {
      throw new BadRequestException(
          "Give exactly one of 'url' (attach an existing repository) or 'name' (create a blank one"
              + " on the platform's git host).");
    }
    if (archetype == null) {
      // The name the repository will be addressable by: the stated one, or — for the attach arm —
      // the url's basename, which is what cloneRepository registers as its alias.
      String addressable =
          trimmedName != null ? trimmedName : RepositoryNameRepository.basename(trimmedUrl);
      archetype = RepositoryArchetype.fromRepositoryName(addressable);
      if (archetype == null) {
        throw new BadRequestException(
            "'"
                + addressable
                + "' carries no role suffix, so there is nothing to read the kind of component out"
                + " of. Either name it after one of "
                + RepositoryArchetype.roleSuffixes()
                + " — the component layout's grammar, <component>[-<modifier>]-<role> — or state an"
                + " archetype outright.");
      }
    }
    if (archetype == RepositoryArchetype.PROJECT) {
      throw new BadRequestException(
          "A repository cannot be created with archetype PROJECT: that archetype is reserved for"
              + " the project's wrapper repository, which is created with the project.");
    }
    if (!archetype.isPlaceable()) {
      throw new BadRequestException(
          "Archetype "
              + archetype
              + " has no directory in the wrapper, so a repository cannot be created under a project"
              + " with it. Placeable archetypes: "
              + RepositoryArchetype.placeableDirectories());
    }
    Repository wrapper =
        findWrapper(projectId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "Project '"
                            + project.name
                            + "' has no wrapper repository, so there is nothing to add a component"
                            + " to. Projects created before wrappers existed have none."));

    Repository repo =
        trimmedName != null
            ? repositoryService.createBlankRepository(project, trimmedName, archetype)
            : repositoryService.cloneRepository(trimmedUrl, archetype, project);

    // The name the wrapper records has to be the name the git host serves this repository under —
    // that is the whole contract of a relative submodule url. A taken name fails the create
    // outright (there is no fallback name), so the read-back cannot differ from the request; it
    // stays because the alias table is the single source of that name.
    String memberName =
        repositoryNameRepository
            .nameFor(repo)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "Repository " + repo.id + " has no addressable name to mount it under."));
    String head =
        repositoryService
            .mainBranchHeadOnHost(repo.id)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "The git host holds no '"
                            + repo.mainBranch
                            + "' branch for "
                            + memberName
                            + " yet, so there is no commit for the wrapper's gitlink to pin."));
    String wrapperPath =
        wrapperSubmoduleWriter.addToWrapper(wrapper, memberName, archetype, component, head);
    // The path the wrapper commit used is the fact, not the request: it is what the reconcile will
    // read back, so recording anything else here would give the row a component its own manifest
    // does not name.
    WrapperPath placed = WrapperPath.parse(wrapperPath);
    String placedComponent = placed == null ? null : placed.component();
    if (placedComponent != null) {
      QuarkusTransaction.requiringNew()
          .run(() -> repositoryService.get(repo.id).component = placedComponent);
      repo.component = placedComponent;
    }
    return new CreatedRepository(repo, wrapperPath);
  }

  /**
   * Registers a repository the git host <b>already serves</b> as a component of {@code projectId}:
   * the row takes the host's storage id and {@code name} becomes its addressable name. The seam
   * behind {@code POST /projects/api/projects/{projectId}/repositories/adopt}.
   *
   * <p><b>It is the third caller of {@link RepositoryService#adoptExistingOrigin}, beside the
   * wrapper reconcile, and the one that does not have to guess.</b> The reconcile finds a bare by
   * asking the host for the entry name used as a storage id, which only answers while the two
   * coordinates coincide. A caller that <em>minted</em> the id — the bootstrap, which creates every
   * platform bare before qits-projects exists to be asked — knows both, and this route is how it
   * says so instead of leaving the reconcile to rediscover it.
   *
   * <p>Nothing is cloned, no mirror is made, and <b>the wrapper is not written</b>: an adopted
   * repository is already a submodule of the project's wrapper, or it is not a component at all.
   * That is the whole difference from {@link #createRepository}, which mints a repository and adds
   * the {@code .gitmodules} entry for it.
   *
   * <p>Idempotent, matched on the storage id: a second call with an id the database already holds
   * answers the row it found, untouched. See {@link RepositoryService#adoptExistingOrigin} for the
   * validation, the alias registration and the 404 when the host holds no such id.
   */
  public Repository adoptRepository(
      String projectId, String repositoryId, String name, String url, RepositoryArchetype archetype) {
    Project project = get(projectId);
    return repositoryService.adoptExistingOrigin(project, repositoryId, name, url, archetype);
  }

  /** Creates the project's wrapper as the last step of project creation. */
  private void createWrapperRepository(Project project, String wrapperUrl) {
    String name = wrapperName(project);
    // Impossible on a fresh project, but load-bearing for the idempotent seed paths that reach the
    // adopt seam below.
    assertNameFree(project, name);
    if (wrapperUrl == null || wrapperUrl.isBlank()) {
      repositoryService.initWrapperOrigin(project, name);
    } else {
      assertWrapperUrlMatches(project, wrapperUrl, name);
      repositoryService.cloneWrapperOrigin(project, wrapperUrl.trim(), name);
    }
  }

  /**
   * The <b>only</b> seam by which a repository becomes a project's wrapper after creation — used by
   * the startup self-seed to retro-fit the {@code qits} project. In-repo configuration deliberately
   * cannot do this: {@code QitsConfigParser} rejects a committed {@code archetype: PROJECT}.
   *
   * <p>Idempotent by promotion, not merely by skip, because the states it must survive differ:
   *
   * <ol>
   *   <li><b>no wrapper</b> — clone {@code url} as the wrapper (seeding the skeleton if the
   *       upstream is empty);
   *   <li><b>a row with this url registered as something else</b> — promote it in place, no
   *       re-clone. This is what makes the retro-fit safe on an instance where someone registered
   *       the url by hand first;
   *   <li><b>already the wrapper with this url</b> — no-op, the steady state on every later boot;
   *   <li><b>an existing url-less wrapper</b> — attach {@code url} as its backup remote. Reached
   *       whenever the project was created greenfield and the manifest later names its upstream.
   * </ol>
   */
  @Transactional
  public Repository adoptWrapperRepository(String projectId, String url) {
    Project project = get(projectId);
    if (url == null || url.isBlank()) {
      throw new BadRequestException("url is required");
    }
    String trimmedUrl = url.trim();
    String name = wrapperName(project);
    assertWrapperUrlMatches(project, trimmedUrl, name);

    Optional<Repository> existingWrapper = findWrapper(projectId);
    if (existingWrapper.isPresent()) {
      Repository wrapper = existingWrapper.get();
      if (trimmedUrl.equals(wrapper.url)) {
        return wrapper; // (3) already adopted
      }
      if (wrapper.url == null || wrapper.url.isBlank()) {
        // (4) created greenfield, now gaining the backup remote the manifest names.
        return repositoryService.attachBackupRemote(wrapper.id, trimmedUrl);
      }
      throw new BadRequestException(
          "Project '"
              + project.name
              + "' already has a wrapper repository backed by "
              + wrapper.url
              + "; a project has at most one.");
    }

    Optional<Repository> sameUrl = repositoryRepository.findByUrlInProject(trimmedUrl, projectId);
    if (sameUrl.isPresent()) {
      // (2) promote in place — the repository is already cloned and served, only its role changes.
      Repository repo = sameUrl.get();
      repo.archetype = RepositoryArchetype.PROJECT;
      repositoryService.registerWrapperAlias(repo, name);
      return repo;
    }

    // (1) no wrapper yet.
    assertNameFree(project, name);
    return repositoryService.cloneWrapperOrigin(project, trimmedUrl, name);
  }

  /**
   * The single check that guarantees local alias == remote basename, i.e. that a committed relative
   * submodule url resolves identically in a workspace container and at the forge.
   */
  private static void assertWrapperUrlMatches(Project project, String url, String name) {
    String basename = RepositoryNameRepository.basename(url);
    if (!name.equals(basename)) {
      throw new BadRequestException(
          "The upstream for project '"
              + project.name
              + "' is named '"
              + basename
              + "', but its wrapper repository must be named '"
              + name
              + "' (a project's wrapper is <slug>-<slug>, and the slug here is '"
              + project.slug
              + "'). Rename the upstream repository, or create the project with a matching slug.");
    }
  }

  private void assertNameFree(Project project, String name) {
    repositoryNameRepository
        .findRepositoryByProjectAndName(project.id, name)
        .ifPresent(
            owner -> {
              throw new BadRequestException(
                  "The name '"
                      + name
                      + "' is already taken in project '"
                      + project.name
                      + "' by repository "
                      + owner.id
                      + "; the wrapper repository must be addressable as '"
                      + name
                      + "' exactly.");
            });
  }
}
