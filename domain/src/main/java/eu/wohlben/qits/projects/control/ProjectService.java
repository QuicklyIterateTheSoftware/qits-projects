package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.validation.ProjectSlugValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {

  /** Slug length cap — see {@code ProjectSlug.PATTERN}, which allows 1-40 characters. */
  private static final int MAX_SLUG_LENGTH = 40;

  @Inject ProjectRepository projectRepository;

  @Inject RepositoryRepository repositoryRepository;

  @Inject RepositoryNameRepository repositoryNameRepository;

  @Inject RepositoryService repositoryService;

  /**
   * Creates a project with its slug <b>derived</b> from {@code name} (see {@link #slugify}) — the
   * convenience form for callers that have no slug of their own to give (the cli seeds, tests).
   *
   * <p>Prefer {@link #create(String, String, String)} wherever the slug is load-bearing: a derived
   * slug is only as stable as the display name it came from.
   */
  @Transactional
  public Project create(String name, String description) {
    return create(name, null, description);
  }

  /** Creates a project with no wrapper upstream — the wrapper is initialized locally. */
  @Transactional
  public Project create(String name, String slug, String description) {
    return create(name, slug, description, null);
  }

  /**
   * Creates a project and, as the <b>last step</b>, its {@linkplain
   * eu.wohlben.qits.projects.entity.RepositoryArchetype#PROJECT wrapper repository} — so
   * project creation always ends with one repository, no matter what.
   *
   * <p>The wrapper is named {@code <slug>-<slug>}: a repository's name is a project-scoped alias
   * served at {@code /git/<projectId>/<name>}, and a committed relative submodule url ({@code
   * ../<name>.git}) folds against the superproject's <em>real backend</em> — so for the two to
   * agree a repository's local alias must equal its remote basename. Forge namespaces are flat,
   * which makes the established convention {@code <project>-<component>}; the wrapper's "component"
   * is the project itself. The name is <b>derived, never supplied</b> — derivation is the
   * enforcement.
   *
   * @param slug the git-safe project identity, or {@code null} to derive it from {@code name}
   * @param wrapperUrl an existing upstream to adopt as the wrapper (brownfield), or {@code null} to
   *     initialize a remote-less one locally (greenfield). An adopted upstream may be completely
   *     empty — it is seeded with the project template skeleton — but its basename must equal
   *     {@code <slug>-<slug>}.
   */
  @Transactional
  public Project create(String name, String slug, String description, String wrapperUrl) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("name is required");
    }

    Project project = new Project();
    project.id = UUID.randomUUID().toString();
    project.name = name;
    project.slug = resolveSlug(name, slug, project.id);
    project.description = description;
    projectRepository.persist(project);

    createWrapperRepository(project, wrapperUrl);
    return project;
  }

  /**
   * Validates an explicitly supplied slug, or derives one from the project name.
   *
   * <p>The Bean Validation constraint on the request DTO only guards HTTP; the self-seed, both cli
   * seeds and MCP all reach {@code create} without passing through it, so the format is re-asserted
   * here — this is the enforcement that actually holds.
   */
  private static String resolveSlug(String name, String slug, String projectId) {
    if (slug == null || slug.isBlank()) {
      return slugify(name, projectId);
    }
    String trimmed = slug.trim();
    if (!ProjectSlugValidator.matches(trimmed)) {
      throw new BadRequestException(
          "Invalid project slug '"
              + trimmed
              + "': must be 1-40 characters of lowercase letters, digits and inner dashes (no"
              + " leading or trailing dash). It becomes a git path segment and a forge repository"
              + " name, so it must survive both unchanged.");
    }
    return trimmed;
  }

  /**
   * Derives a git-safe slug from a display name: lowercase, every run of non-alphanumerics becomes
   * a dash, leading/trailing dashes stripped, capped at 40 characters.
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

  @Transactional
  public Project update(String id, String name, String description) {
    Project project = get(id);

    if (name != null && !name.isBlank()) {
      project.name = name;
    }
    if (description != null) {
      project.description = description;
    }

    return project;
  }

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

  @Transactional
  public Repository createRepositoryUnderProject(
      String projectId, String url, RepositoryArchetype archetype, boolean importSubmodules) {
    Project project = get(projectId);
    // The wrapper is never created through the ordinary repositories path: it is derived from the
    // project's slug and owned by adoptWrapperRepository, which is the single seam that may mint or
    // promote one. Allowing it here would let a second wrapper in past the guard.
    if (archetype == RepositoryArchetype.PROJECT) {
      throw new BadRequestException(
          "A repository cannot be created with archetype PROJECT: that archetype is reserved for the"
              + " project's wrapper repository, which is created with the project.");
    }

    return repositoryService.cloneRepository(url, archetype, project, importSubmodules);
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
   *
   * <p>Both mutating states rewrite the metadata sidecar, without which repository discovery would
   * restore the pre-change values from disk on the next boot.
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
      repositoryService.rewriteMetadata(repo);
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
