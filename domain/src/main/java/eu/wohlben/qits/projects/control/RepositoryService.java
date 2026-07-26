package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.domain.process.control.RepoProcessLease;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.process.dto.TechnicalProcessFrame;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.dto.BranchDto;
import eu.wohlben.qits.projects.dto.SyncStatusDto;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositorySubmodule;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.persistence.RepositorySubmoduleRepository;
import eu.wohlben.qits.projects.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RepositoryService {

  private static final Logger LOG = Logger.getLogger(RepositoryService.class);

  /** Backstop for the recursive submodule-closure import (the cycle guard's belt-and-braces). */
  private static final int MAX_SUBMODULE_DEPTH = 10;

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject MetadataService metadataService;

  @Inject WorkspaceService workspaceService;

  @Inject ContainerRuntime containerRuntime;

  @Inject GitExecutor git;

  @Inject GitIdentity gitIdentity;

  @Inject GitRemoteAuth remoteAuth;

  @Inject GitSubmoduleParser submoduleParser;

  @Inject RepositorySubmoduleRepository repositorySubmoduleRepository;

  @Inject RepositoryNameRepository repositoryNameRepository;

  @Inject ProjectTemplate projectTemplate;

  @Inject TechnicalProcessRegistry processes;

  /**
   * Runs {@link #beginPullRepository}'s recursive pull off the request thread — the HTTP call
   * returns the technical-process id immediately and the browser watches the walk repo by repo over
   * SSE. Mirrors {@code WorkspaceService}'s provision executor.
   */
  private final ExecutorService processExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "repository-pull");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    processExecutor.shutdownNow();
  }

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  /** Clones without importing submodules — the plain primitive (also what child imports use). */
  @Transactional
  public Repository cloneRepository(String url, RepositoryArchetype archetype, Project project) {
    return cloneOne(url, archetype, project, true);
  }

  /**
   * Clones and, when {@code importSubmodules}, imports the repository's <b>full submodule
   * closure</b> as sibling repositories under the same project (recursive, dedup + cycle-guarded,
   * depth-capped). The whole closure is imported because provisioning materializes submodules by
   * native relative resolution, so every submodule git resolves — at every depth — must already be
   * a servable sibling.
   */
  @Transactional
  public Repository cloneRepository(
      String url, RepositoryArchetype archetype, Project project, boolean importSubmodules) {
    Repository repo = cloneOne(url, archetype, project, true);
    if (importSubmodules) {
      importDirectSubmodules(repo);
    }
    return repo;
  }

  /**
   * Clones one repository under {@code project} and registers its project-scoped name alias (its
   * url basename) — no submodule handling here; that is {@link #importDirectSubmodules}'s job (the
   * full closure, recursively). Runs within the caller's transaction.
   *
   * @param createMainWorkspace whether to give the repository its default main-branch workspace —
   *     {@code true} for a user-created repository, {@code false} for an imported child (a
   *     submodule materializes inside its superproject's container, not as an independent sibling
   *     workspace; the child stays usable standalone since {@code createMainWorkspace} is
   *     idempotent).
   */
  private Repository cloneOne(
      String url, RepositoryArchetype archetype, Project project, boolean createMainWorkspace) {
    return cloneOne(url, archetype, project, createMainWorkspace, null, false);
  }

  /**
   * Clones a project's <b>wrapper repository</b> from an upstream that may be completely empty —
   * the brownfield half of wrapper creation.
   *
   * <p>{@code git clone --mirror} of an empty remote succeeds but yields no refs, and there is no
   * {@code HEAD} for {@link #detectDefaultBranch} to read (it would answer {@code "master"}).
   * Rather than requiring a README be pushed first, HEAD is pointed at {@link
   * #WRAPPER_DEFAULT_BRANCH} and the skeleton is seeded there — so a brand-new, never-pushed-to
   * forge repository is a supported starting state. An upstream that <em>does</em> have history is
   * left completely untouched.
   *
   * @param name the wrapper's project-scoped addressable name, {@code <slug>-<slug>}
   */
  @Transactional
  public Repository cloneWrapperOrigin(Project project, String url, String name) {
    return cloneOne(url, RepositoryArchetype.PROJECT, project, true, name, true);
  }

  /**
   * @param selfName the addressable name to register, or {@code null} to derive it from the url
   *     basename as usual
   * @param seedSkeletonIfEmpty whether an upstream that came back with no refs at all should be
   *     given the project template skeleton on {@link #WRAPPER_DEFAULT_BRANCH}
   */
  private Repository cloneOne(
      String url,
      RepositoryArchetype archetype,
      Project project,
      boolean createMainWorkspace,
      String selfName,
      boolean seedSkeletonIfEmpty) {
    if (url == null || url.isBlank()) {
      throw new BadRequestException("url is required");
    }
    String trimmedUrl = url.trim();
    // `url` is user-supplied and passed to `git clone`. Reject a dash-leading value so it can't be
    // smuggled in as a flag (argv flag injection), and the `ext::` transport which lets a remote
    // run
    // arbitrary commands. Local paths and https/ssh/git remotes are all still allowed.
    if (trimmedUrl.startsWith("-") || trimmedUrl.regionMatches(true, 0, "ext::", 0, 5)) {
      throw new BadRequestException("Invalid repository URL: " + trimmedUrl);
    }
    // A repository must point at its real backend, never at qits' own git host — cloning from
    // /git/… would mirror qits' cache back onto itself (a self-referential loop) instead of the
    // upstream. This also enforces the submodule onboarding convention downstream (a child imported
    // from a resolved qits-host url is the exact "points at the qits host" bug the guard prevents).
    if (submoduleParser.isQitsHostUrl(trimmedUrl)) {
      throw new BadRequestException(
          "Refusing to clone from the qits git host ("
              + trimmedUrl
              + "); a repository must point at its real backend, not qits' own cache.");
    }

    Repository repo = new Repository();
    repo.id = UUID.randomUUID().toString();
    repo.url = trimmedUrl;
    repo.archetype = archetype != null ? archetype : RepositoryArchetype.SERVICE;
    repo.project = project;
    repositoryRepository.persist(repo);

    // Give the repository a project-scoped addressable name (its url basename) so the git host can
    // serve it as a sibling under /git/<projectId>/<name> — this is what lets committed relative
    // submodule urls resolve natively, and what its own workspace container clones itself under.
    if (selfName != null) {
      registerWrapperName(repo, selfName);
    } else {
      repositoryNameRepository.registerSelfName(repo);
    }

    Path originPath = Path.of(dataDir, repo.id, "origin");
    try {
      Files.createDirectories(originPath.getParent());
      git.exec(
          null,
          remoteAuth.gitWithCredentials(
              "clone", "--mirror", "--end-of-options", repo.url, originPath.toString()));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git clone failed: " + e.getMessage());
    }

    // An empty upstream mirrors successfully but brings no refs, leaving nothing for a workspace
    // container's clone to land on. Give it the skeleton on `main` instead of demanding the user
    // push a first commit by hand — this is what makes a brand-new, never-pushed-to forge
    // repository
    // a supported starting state. Never reached for an upstream that has history.
    if (seedSkeletonIfEmpty && !hasAnyRef(originPath)) {
      try {
        git.exec(
            originPath.toFile(),
            "git",
            "symbolic-ref",
            "HEAD",
            "refs/heads/" + WRAPPER_DEFAULT_BRANCH);
      } catch (Exception e) {
        throw new InternalServerErrorException(
            "Failed to point the empty mirror's HEAD at " + WRAPPER_DEFAULT_BRANCH);
      }
      seedProjectTemplate(repo.id, originPath, WRAPPER_DEFAULT_BRANCH);
    }

    // The main branch defaults to the remote's default branch (the mirror's HEAD).
    repo.mainBranch = detectDefaultBranch(originPath);

    metadataService.writeRepositoryMetadata(repo);

    // Every repository starts with a default workspace checked out on its main branch, so the main
    // branch is immediately workable and appears as a workspace-backed root in the branch tree.
    // Suppressed for imported children — they materialize inside their superproject's container.
    if (createMainWorkspace) {
      workspaceService.createMainWorkspace(repo.id, repo.mainBranch);
    }

    return repo;
  }

  /**
   * Imports {@code repoId}'s <b>full submodule closure</b> as sibling repositories under the same
   * project (every level, recursively), each linked by a {@link RepositorySubmodule} edge, and
   * returns {@code repoId}'s direct edge list afterwards. The whole closure is imported because
   * provisioning clones with native {@code --recurse-submodules}: every submodule git resolves, at
   * any depth, must already be a servable sibling. Idempotent: children dedup by resolved url
   * within the project, edges by (parent, path), so re-invoking imports only what's missing.
   * Cycle-guarded (a visited set — the mutual {@code submodule-cycle-a/b} pair links back to the
   * existing row) and depth-capped.
   */
  @Transactional
  public List<RepositorySubmodule> importDirectSubmodules(String repoId) {
    Repository repo = get(repoId);
    importSubmoduleClosure(repo, new HashSet<>(), 0);
    return repositorySubmoduleRepository.findByParentId(repoId);
  }

  private void importDirectSubmodules(Repository repo) {
    importSubmoduleClosure(repo, new HashSet<>(), 0);
  }

  /**
   * Recursively imports {@code repo}'s submodule closure. {@code visited} (by repository id) makes
   * a cyclic import graph terminate — a repo already visited on this walk is not re-descended — and
   * {@code depth} is the backstop cap. For each {@code .gitmodules} entry: resolve the child url,
   * dedup-or-create the sibling repository (which registers its own url-basename alias via {@code
   * cloneOne}), register the referencing name as an alias of the child (idempotent; usually equals
   * the child's own basename, but the link table supports a child addressed by more than one name),
   * link the edge, and descend.
   */
  private void importSubmoduleClosure(Repository repo, Set<String> visited, int depth) {
    if (depth >= MAX_SUBMODULE_DEPTH || !visited.add(repo.id)) {
      return;
    }
    Path originPath = originPath(repo.id);
    for (GitSubmoduleParser.Submodule sub :
        submoduleParser.readSubmodules(originPath.toFile(), repo.mainBranch)) {
      // A RELATIVE url folds against the superproject's real backend; with no backup remote
      // configured there is nothing to fold it against, and resolveSubmoduleUrl would NPE. An
      // absolute url ignores the superproject's url entirely, so those keep importing normally.
      if (!hasBackupRemote(repo) && isRelativeSubmoduleUrl(sub.url())) {
        throw new BadRequestException(
            "Submodule '"
                + sub.name()
                + "' uses a relative url ("
                + sub.url()
                + ") but repository '"
                + repoLabel(repo)
                + "' has no backup remote configured, so there is nothing to resolve it against."
                + " Configure the backup remote first, or commit an absolute url.");
      }
      String childUrl = submoduleParser.resolveSubmoduleUrl(repo.url, sub.url());

      // A submodule whose url resolves to qits' own git host would make the imported sister clone
      // from qits itself (a caching loop) rather than the real backend. Fail loudly with the
      // onboarding convention: commit a relative url (../name.git, which folds against the
      // superproject's real backend) or pre-serve the backend and reference it relatively. See
      // docs/epics/qits-project-repository-submodules/features/2026-07-25_submodule-backend-onboarding.md.
      if (submoduleParser.isQitsHostUrl(childUrl)) {
        throw new BadRequestException(
            "Submodule '"
                + sub.name()
                + "' resolves to the qits git host ("
                + childUrl
                + "). Reference it with a relative url (../"
                + RepositoryNameRepository.basename(childUrl)
                + ".git) so it folds against the real backend, not qits' cache.");
      }

      // Dedup within the project: reuse an existing sibling with the same url (the diamond case —
      // and what terminates a cyclic pair: its second import finds the first repo already there).
      // Panache auto-flushes before this query, so a child imported earlier in this same
      // transaction is already visible.
      Repository child =
          repositoryRepository.findByUrlInProject(childUrl, repo.project.id).orElse(null);
      if (child == null) {
        child = cloneOne(childUrl, RepositoryArchetype.SERVICE, repo.project, false);
      }

      // Register the referencing name as an alias of the child so /git/<projectId>/<name> resolves
      // it; native `../<name>.git` from this superproject then lands on the sibling with no
      // override.
      repositoryNameRepository.ensureAlias(
          repo.project, RepositoryNameRepository.basename(childUrl), child);

      if (!repositorySubmoduleRepository.existsByParentAndPath(repo.id, sub.path())) {
        RepositorySubmodule edge = new RepositorySubmodule();
        edge.parent = repo;
        edge.child = child;
        edge.path = sub.path();
        edge.name = sub.name();
        repositorySubmoduleRepository.persist(edge);
      }

      importSubmoduleClosure(child, visited, depth + 1);
    }
  }

  /** The served sibling a {@link #prepareSubmoduleBackend} onboarding produced. */
  public record PreparedSubmoduleBackend(
      String repositoryId, String name, String relativeUrl, String backendUrl) {}

  /**
   * Pre-serves a submodule's backend as a sibling repository so an in-container {@code git
   * submodule add ../<name>.git <path>} resolves <em>before</em> the {@code .gitmodules} reference
   * is committed — breaking the submodule chicken-and-egg (the sibling must be servable at {@code
   * /git/<projectId>/<name>} for the add, but is only imported after the commit).
   *
   * <p>The sibling is cloned from the <b>canonical</b> url the superproject's own re-import will
   * resolve for {@code ../<name>.git} ({@link GitSubmoduleParser#resolveSubmoduleUrl} against the
   * superproject's real backend), <em>not</em> the raw {@code backendUrl} string — so a later
   * {@code importDirectSubmodules} dedups onto this very sibling (dedup is by exact url) instead of
   * creating a duplicate. For the common case (a submodule that is a sibling of the superproject's
   * backend, e.g. {@code qits-gateway} alongside {@code qits-backend} under one org) the canonical
   * url equals {@code backendUrl}; the returned {@code backendUrl} surfaces the resolved value so a
   * cross-host mismatch is visible. {@code backendUrl} is used only to name the sibling (its
   * basename). Idempotent: an existing project sibling with the canonical url is reused.
   */
  @Transactional
  public PreparedSubmoduleBackend prepareSubmoduleBackend(
      String superprojectId, String backendUrl) {
    Repository superproject = get(superprojectId);
    if (backendUrl == null || backendUrl.isBlank()) {
      throw new BadRequestException("backendUrl is required");
    }
    String trimmed = backendUrl.trim();
    if (submoduleParser.isQitsHostUrl(trimmed)) {
      throw new BadRequestException(
          "backendUrl must be the submodule's real backend, not the qits git host: " + trimmed);
    }
    String name = RepositoryNameRepository.basename(trimmed);
    if (name.isBlank()) {
      throw new BadRequestException(
          "Could not derive a submodule name from backendUrl: " + trimmed);
    }
    String relativeUrl = "../" + name + ".git";
    // The pre-serve clones from the canonical url the superproject's own re-import will resolve for
    // ../<name>.git — which only exists if the superproject has a backend to fold it against.
    if (!hasBackupRemote(superproject)) {
      throw new BadRequestException(
          "Cannot pre-serve a submodule backend under '"
              + repoLabel(superproject)
              + "': it has no backup remote configured, so "
              + relativeUrl
              + " has nothing to resolve against. Configure the backup remote first.");
    }
    String canonicalUrl = submoduleParser.resolveSubmoduleUrl(superproject.url, relativeUrl);
    Repository sibling =
        repositoryRepository
            .findByUrlInProject(canonicalUrl, superproject.project.id)
            .orElseGet(
                () ->
                    cloneOne(
                        canonicalUrl, RepositoryArchetype.SERVICE, superproject.project, true));
    // Re-assert the basename alias (cloneOne already registered it for a fresh clone; harmless and
    // needed on the reuse path).
    repositoryNameRepository.ensureAlias(superproject.project, name, sibling);
    return new PreparedSubmoduleBackend(sibling.id, name, relativeUrl, canonicalUrl);
  }

  /**
   * The DIRECT {@code .gitmodules} submodules of {@code repoId} that are not yet imported (no edge
   * at their path) — what the repository detail view's "import submodules" action offers. Urls come
   * back resolved (relative names folded against the repository's own url).
   */
  public List<GitSubmoduleParser.Submodule> listUnimportedSubmodules(String repoId) {
    Repository repo = get(repoId);
    Set<String> importedPaths =
        repositorySubmoduleRepository.findByParentId(repoId).stream()
            .map(edge -> edge.path)
            .collect(java.util.stream.Collectors.toSet());
    return submoduleParser.readSubmodules(originPath(repo.id).toFile(), repo.mainBranch).stream()
        .filter(sub -> !importedPaths.contains(sub.path()))
        .map(
            sub ->
                new GitSubmoduleParser.Submodule(
                    sub.name(), sub.path(), resolveAgainst(repo, sub.url()), sub.branch()))
        .toList();
  }

  /**
   * {@link GitSubmoduleParser#resolveSubmoduleUrl} for read paths: a relative url under a
   * repository with no backup remote has nothing to fold against, so the raw value is surfaced
   * unresolved rather than throwing. Listing what a repository declares must never fail on it.
   */
  private String resolveAgainst(Repository repo, String rawUrl) {
    if (!hasBackupRemote(repo) && isRelativeSubmoduleUrl(rawUrl)) {
      return rawUrl;
    }
    return submoduleParser.resolveSubmoduleUrl(repo.url, rawUrl);
  }

  /**
   * Whether a committed submodule url is relative, i.e. resolved against the superproject's url.
   */
  private static boolean isRelativeSubmoduleUrl(String rawUrl) {
    String trimmed = rawUrl == null ? "" : rawUrl.trim();
    return trimmed.startsWith("./") || trimmed.startsWith("../");
  }

  private Path originPath(String repoId) {
    return Path.of(dataDir, repoId, "origin");
  }

  /**
   * A repository's human identity for process segment names and log lines. {@code Repository} has
   * no display name, so this is its project-scoped alias — which {@code registerSelfName}
   * guarantees at creation, and which is defined even for a wrapper that has no url to take a
   * basename from.
   */
  private String repoLabel(Repository repo) {
    return repositoryNameRepository.nameFor(repo).orElse(repo.id);
  }

  /**
   * Whether a backup remote is configured — a greenfield wrapper has none until one is attached.
   */
  private static boolean hasBackupRemote(Repository repo) {
    return repo.url != null && !repo.url.isBlank();
  }

  /**
   * The default branch a wrapper repository is born on. A greenfield origin has no remote to
   * inherit a default from, and {@link #detectDefaultBranch} would answer {@code "master"}.
   */
  static final String WRAPPER_DEFAULT_BRANCH = "main";

  /**
   * Creates a project's <b>wrapper repository</b> with a locally-initialized, remote-less bare
   * origin, seeded with the {@link ProjectTemplate} skeleton — the greenfield half of wrapper
   * creation, the sibling of {@link #cloneOne} for a project that has no upstream at all.
   *
   * <p>{@code git init --bare} yields no {@code HEAD} a {@link #detectDefaultBranch} could read, so
   * HEAD is pointed at {@link #WRAPPER_DEFAULT_BRANCH} explicitly and the skeleton commit is what
   * gives that branch a commit to resolve to. Without it a workspace container's clone would land
   * on an unborn branch.
   *
   * <p>{@code url} stays null: a wrapper has no backup remote until one is attached ({@link
   * #attachBackupRemote}). Runs within the caller's transaction.
   *
   * @param name the wrapper's project-scoped addressable name, {@code <slug>-<slug>}
   */
  @Transactional
  public Repository initWrapperOrigin(Project project, String name) {
    Repository repo = new Repository();
    repo.id = UUID.randomUUID().toString();
    repo.url = null;
    repo.archetype = RepositoryArchetype.PROJECT;
    repo.project = project;
    repo.mainBranch = WRAPPER_DEFAULT_BRANCH;
    repositoryRepository.persist(repo);

    // The wrapper's alias must be exactly <slug>-<slug>, never the disambiguated fallback — see
    // registerWrapperName.
    registerWrapperName(repo, name);

    Path originPath = originPath(repo.id);
    try {
      Files.createDirectories(originPath.getParent());
      git.exec(null, "git", "init", "--bare", "--end-of-options", originPath.toString());
      git.exec(
          originPath.toFile(),
          "git",
          "symbolic-ref",
          "HEAD",
          "refs/heads/" + WRAPPER_DEFAULT_BRANCH);
    } catch (Exception e) {
      throw new InternalServerErrorException(
          "Failed to initialize the wrapper repository origin: " + e.getMessage());
    }

    seedProjectTemplate(repo.id, originPath, WRAPPER_DEFAULT_BRANCH);
    metadataService.writeRepositoryMetadata(repo);
    workspaceService.createMainWorkspace(repo.id, repo.mainBranch);
    return repo;
  }

  /**
   * Registers the wrapper's addressable name, refusing to fall back to {@code
   * RepositoryNameRepository}'s {@code <name>-<idPrefix>} disambiguation.
   *
   * <p>The whole point of the {@code <slug>-<slug>} rule is that a wrapper's <b>local alias equals
   * its remote basename</b>, which is what makes a committed relative submodule url ({@code
   * ../<name>.git}) resolve identically in a workspace container and at the forge. Silently
   * accepting {@code qits-qits-a1b2c3d4} would destroy that invariant without any error, so a taken
   * name is a hard failure instead.
   */
  private void registerWrapperName(Repository repo, String name) {
    repositoryNameRepository
        .findRepositoryByProjectAndName(repo.project.id, name)
        .filter(owner -> !owner.id.equals(repo.id))
        .ifPresent(
            owner -> {
              throw new BadRequestException(
                  "The name '"
                      + name
                      + "' is already taken in this project by repository "
                      + owner.id
                      + "; a project's wrapper repository must be addressable as '"
                      + name
                      + "' exactly.");
            });
    repositoryNameRepository.registerSelfName(repo, name);
  }

  /**
   * Attaches a backup remote to a repository that has none — the wrapper created greenfield, which
   * later gains the forge repository it should be backed up to.
   *
   * <p>Sets {@code url}, adds the {@code origin} remote in the bare with a mirror refspec (so
   * {@code ls-remote origin}, pull and push behave exactly as for a cloned origin), and <b>rewrites
   * the metadata sidecar</b> — without that last step {@code RepositoryDiscoveryService} restores
   * the null url from the sidecar on the next boot and the attachment silently undoes itself.
   */
  @Transactional
  public Repository attachBackupRemote(String repoId, String url) {
    Repository repo = get(repoId);
    if (url == null || url.isBlank()) {
      throw new BadRequestException("url is required");
    }
    String trimmedUrl = url.trim();
    if (trimmedUrl.startsWith("-") || trimmedUrl.regionMatches(true, 0, "ext::", 0, 5)) {
      throw new BadRequestException("Invalid repository URL: " + trimmedUrl);
    }
    if (submoduleParser.isQitsHostUrl(trimmedUrl)) {
      throw new BadRequestException(
          "Refusing to configure the qits git host ("
              + trimmedUrl
              + ") as a backup remote; it must point at the real backend, not qits' own cache.");
    }
    if (repo.url != null && !repo.url.isBlank()) {
      throw new BadRequestException(
          "Repository already has a backup remote configured (" + repo.url + ").");
    }

    repo.url = trimmedUrl;
    Path originPath = originPath(repo.id);
    try {
      // Best-effort: a bare initialized by initWrapperOrigin has no remote yet, but re-running must
      // not fail on "remote origin already exists".
      git.execAllowNonZero(
          originPath.toFile(), "git", "remote", "add", "--mirror=fetch", "origin", trimmedUrl);
      git.exec(originPath.toFile(), "git", "remote", "set-url", "origin", trimmedUrl);
    } catch (Exception e) {
      throw new InternalServerErrorException(
          "Failed to configure the backup remote: " + e.getMessage());
    }
    metadataService.writeRepositoryMetadata(repo);
    return repo;
  }

  /**
   * Registers {@code name} as an addressable alias of an <em>existing</em> repository being
   * promoted to wrapper. Same no-disambiguation contract as {@link #registerWrapperName}.
   */
  public void registerWrapperAlias(Repository repo, String name) {
    registerWrapperName(repo, name);
  }

  /**
   * Rewrites the on-disk metadata sidecar from the row. Mandatory after any change to {@code url}
   * or {@code archetype} outside the clone path: repository discovery restores both fields from the
   * sidecar on every boot, so a change not written back is silently reverted.
   */
  public void rewriteMetadata(Repository repo) {
    metadataService.writeRepositoryMetadata(repo);
  }

  /** Whether the origin has any branch at all — false for a freshly-initialized or empty mirror. */
  private boolean hasAnyRef(Path originPath) {
    try {
      return !git.exec(
              originPath.toFile(),
              "git",
              "for-each-ref",
              "--count=1",
              "--format=%(refname)",
              "refs/heads/")
          .trim()
          .isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Commits the {@link ProjectTemplate} skeleton as the <b>root commit</b> of {@code branch},
   * directly in the bare origin.
   *
   * <p>Written with plumbing and no worktree — the same commit-without-checkout technique {@link
   * #mergeDivergedRemote} uses, extended with a temporary index so a nested tree can be built:
   * {@code hash-object} every blob, {@code update-index --add --cacheinfo <mode>,<sha>,<path>} them
   * into a scratch index, then {@code write-tree} (which builds the subtrees, unlike {@code
   * mktree}) + {@code commit-tree} + {@code update-ref}. The explicit per-entry mode is what lets
   * {@code CLAUDE.md} land as a real git symlink ({@code 120000}) rather than a file.
   *
   * <p>The scratch lives at {@code <data-dir>/<repoId>/skeleton/}, a sibling of {@code origin}
   * rather than {@code /tmp}: {@link #deleteDataDir} already reaps it if anything leaks, the test
   * suite's per-class data-dir reset wipes it, and {@code RepositoryDiscoveryService} keys on the
   * presence of {@code origin} so a stray sibling directory is invisible to it.
   *
   * <p>Only ever called for an origin with nothing to lose — a fresh {@code init} or a mirror that
   * came back with no refs at all. It never overwrites or merges into existing history.
   */
  void seedProjectTemplate(String repoId, Path originPath, String branch) {
    List<ProjectTemplate.TemplateEntry> entries = projectTemplate.entries();
    Path scratch = Path.of(dataDir, repoId, "skeleton");
    Path tree = scratch.resolve("tree");
    Path index = scratch.resolve("index");
    try {
      // Materialize the blobs so `git hash-object` can read them as files (GitExecutor has no stdin
      // seam, and a file list keeps this to one process for the whole template).
      List<String> hashArgs = new ArrayList<>(List.of("git", "hash-object", "-w", "--no-filters"));
      for (ProjectTemplate.TemplateEntry entry : entries) {
        Path file = tree.resolve(entry.path());
        Files.createDirectories(file.getParent());
        Files.write(file, entry.content());
        hashArgs.add(file.toAbsolutePath().toString());
      }

      List<String> shas =
          git.exec(originPath.toFile(), hashArgs.toArray(String[]::new))
              .lines()
              .map(String::trim)
              .filter(line -> !line.isEmpty())
              .toList();
      if (shas.size() != entries.size()) {
        throw new InternalServerErrorException(
            "Expected " + entries.size() + " template blobs, got " + shas.size());
      }

      // One update-index for every entry. The index is flat, so nested paths need no directory
      // entries — write-tree derives the subtrees.
      List<String> indexArgs = new ArrayList<>(List.of("git", "update-index", "--add"));
      for (int i = 0; i < entries.size(); i++) {
        ProjectTemplate.TemplateEntry entry = entries.get(i);
        indexArgs.add("--cacheinfo");
        indexArgs.add(entry.mode() + "," + shas.get(i) + "," + entry.path());
      }
      var indexEnv = java.util.Map.of("GIT_INDEX_FILE", index.toAbsolutePath().toString());
      git.exec(originPath.toFile(), indexEnv, indexArgs.toArray(String[]::new));

      String treeSha = git.exec(originPath.toFile(), indexEnv, "git", "write-tree").trim();

      // A root commit: no -p. Attributed like every other commit qits manufactures.
      List<String> commitArgs = new ArrayList<>(List.of("git"));
      commitArgs.addAll(gitIdentity.inlineArgs());
      commitArgs.addAll(
          List.of("commit-tree", treeSha, "-m", "Initialize the project template skeleton"));
      String commitSha =
          git.exec(originPath.toFile(), gitIdentity.envMap(), commitArgs.toArray(String[]::new))
              .trim();

      git.exec(originPath.toFile(), "git", "update-ref", "refs/heads/" + branch, commitSha);
      LOG.infof("Seeded the project template skeleton on '%s' of repository %s", branch, repoId);
    } catch (Exception e) {
      throw new InternalServerErrorException(
          "Failed to seed the project template skeleton: " + e.getMessage());
    } finally {
      deleteRecursively(scratch);
    }
  }

  /** The mirror's HEAD points at the remote's default branch (e.g. "master"/"main"). */
  private String detectDefaultBranch(Path originPath) {
    try {
      return git.exec(originPath.toFile(), "git", "symbolic-ref", "--short", "HEAD").trim();
    } catch (Exception e) {
      return "master";
    }
  }

  /** The configured main branch, falling back to the remote's default branch. */
  private String resolveMainBranch(Repository repo, Path originPath) {
    if (repo.mainBranch != null && !repo.mainBranch.isBlank()) {
      return repo.mainBranch;
    }
    return detectDefaultBranch(originPath);
  }

  /**
   * Pulls the remote's main branch into the local mirror. Fetches the branch and fast-forwards the
   * local ref when the remote is strictly ahead; a no-op when already up to date or locally ahead.
   * Diverged histories are reconciled rather than refused: a cleanly-mergeable divergence becomes a
   * real merge commit on the branch, and a conflicting one parks the remote tip on {@code
   * merge/<branch>-origin-<branch>} (overwriting a previous attempt) and fails with the resolution
   * path in the message — see {@link #mergeDivergedRemote}. After its own pull, recursively pulls
   * the repository's IMPORTED submodule children (sibling repositories) — a gitlink bump arriving
   * on the superproject's main branch must never point at a commit the child sibling's origin does
   * not yet have, or the workspace container's {@code submodule update} (which clones from that
   * origin via the git host) fails with "Server does not allow request for unadvertised object".
   */
  public String pullRepository(String repoId) {
    return pullRepository(repoId, new HashSet<>(), null, null, new HashSet<>());
  }

  /**
   * The streamed pull: registers a repository-scoped {@link TechnicalProcess} <em>before</em> any
   * git runs (so the currently-fetching repo is visible while its {@code git fetch} blocks on the
   * network), runs the recursive walk on a worker thread, and returns the process id immediately.
   * The browser watches the walk repo by repo over the process's SSE stream — one segment per
   * pulled repository; failures surface there (live, untruncated), not as an HTTP error. Throws 404
   * in-request when the repository doesn't exist, so a bad id still fails fast. Mirrors {@code
   * WorkspaceService.beginEnsureContainer}.
   *
   * <p>Kind-aware single-flight (see {@link TechnicalProcessRegistry#beginForRepository}): a live
   * pull for this repo is reused (its id is returned, no second walk — two walks race the bare
   * origin's ref-locks); a live <em>sync</em> is a conflict (a pull can't ride a sync's push
   * semantics), rejected with a 400. This closes the race even for a client that never learned a
   * pull was running (dialog closed, button clicked again).
   */
  public String beginPullRepository(String repoId) {
    // Validate in-request (unknown id → plain 404, not a process) and name the root segment by the
    // repo's url basename — Repository has no display name; this is the identity the WARNING lines
    // (and reposByName in tests) already use.
    String rootSegment =
        QuarkusTransaction.requiringNew().call(() -> "pull:" + repoLabel(get(repoId)));
    return switch (processes.beginForRepository(repoId, "pull")) {
      case RepoProcessLease.Reused r -> r.processId();
      case RepoProcessLease.Conflict c -> throw repositoryBusy(c.runningKind());
      case RepoProcessLease.Fresh f -> {
        TechnicalProcess process = f.process();
        // Segment names double as the segment key, so they must be unique across the whole walk:
        // two
        // repos reached under the same relative path (nested levels, or a child path equal to the
        // root basename) would otherwise collide and a failed one's verdict would be swallowed by
        // the first's `ok`. Threaded through the recursion, this allocator disambiguates a repeat
        // with a suffix.
        Set<String> usedSegments = new HashSet<>();
        usedSegments.add(rootSegment);
        processExecutor.submit(
            () -> {
              try {
                pullRepository(repoId, new HashSet<>(), process, rootSegment, usedSegments);
                // No asynchronous second phase: declare an empty service set and settle the
                // provision
                // so `done` fires immediately. A child segment settled `failed` still makes
                // finish()
                // compute overall `done failed`.
                process.expectServices(List.of());
                process.finishProvision(true);
              } catch (RuntimeException e) {
                // Root failure (diverged branch, unreachable remote, auth wall): settle the open
                // root segment failed (appending the message) and emit `done failed`. Idempotent.
                failWithAuthHint(process, e.getMessage(), repoId);
                LOG.debugf(e, "Streamed pull failed for repository %s", repoId);
              }
            });
        yield process.id();
      }
    };
  }

  /**
   * A pull and a sync can't share a walk (a pull would skip the push) nor safely run concurrently
   * against the same bare origin, so a cross-kind request while one is live is rejected. In
   * practice the frontend guard disables the buttons while any repo process is live, so this only
   * ever fires for a second tab / API client that hasn't yet learned a process is running.
   */
  private BadRequestException repositoryBusy(String runningKind) {
    return new BadRequestException(
        "A " + runningKind + " is already running for this repository; wait for it to finish.");
  }

  /**
   * Settle {@code segment} failed, classifying an auth-wall failure with the {@code remote-auth}
   * hint whose target is {@code authRepoId} — the repository whose remote to sign into. For a
   * submodule child that is the <em>child</em>'s id, not the root's, so the sign-in terminal seeds
   * the credentials for the host that actually rejected.
   */
  private void settleWithAuthHint(
      TechnicalProcess process, String segment, String message, String authRepoId) {
    boolean auth = GitRemoteAuth.isAuthFailure(message);
    process.settleSegment(
        segment,
        false,
        auth ? TechnicalProcessFrame.HINT_REMOTE_AUTH : null,
        auth ? authRepoId : null);
  }

  /**
   * {@link #settleWithAuthHint} for the whole-process failure path (settles every open segment).
   */
  private void failWithAuthHint(TechnicalProcess process, String message, String authRepoId) {
    boolean auth = GitRemoteAuth.isAuthFailure(message);
    process.failProvision(
        message, auth ? TechnicalProcessFrame.HINT_REMOTE_AUTH : null, auth ? authRepoId : null);
  }

  /** The scalar snapshot a single repo's pull needs, read in one short transaction. */
  private record PullContext(String url, String branch, Path workdir, boolean hasMainWorkspace) {}

  /** A submodule edge flattened to scalars, so it outlives the transaction that loaded it. */
  private record ChildEdge(String path, String childId, String childUrl) {}

  /**
   * {@link #pullRepository(String)} with the recursion state over the imported submodule edge graph
   * and an optional {@link TechnicalProcess} sink: {@code visited} both terminates cycles (the
   * {@code submodule-cycle-*} pair) and dedups the diamond (a shared child is pulled once per
   * invocation — so it gets exactly one segment, under the first edge that reached it, and a cycle
   * never reopens one). With a process, this repo's own pull is streamed as {@code segmentName}
   * (opened at entry, settled when it completes); {@code process}/{@code segmentName} are null for
   * the synchronous callers ({@code syncRepository}), leaving them untouched.
   *
   * <p>Runs on a worker thread when streaming, so every DB touch opens its own transaction.
   */
  private String pullRepository(
      String repoId,
      Set<String> visited,
      TechnicalProcess process,
      String segmentName,
      Set<String> usedSegments) {
    if (!visited.add(repoId)) {
      return "";
    }
    if (process != null && segmentName != null) {
      process.openSegment(segmentName);
    }

    // The main branch lives in its default workspace, so pull there: git refuses to fetch-update a
    // ref that is checked out, and updating it behind the workspace's back would desync the working
    // tree. We fetch by URL (into FETCH_HEAD, not refs/heads/*) and fast-forward the workspace,
    // which moves the ref and the checkout together. Fall back to the bare origin for a repo with
    // no main workspace.
    PullContext ctx =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo = get(repoId);
                  Path originPath = requireOrigin(repoId);
                  String branch = resolveMainBranch(repo, originPath);
                  Optional<Path> mainWorkspace =
                      workspaceService.workspacePathForBranch(repoId, branch);
                  return new PullContext(
                      repo.url,
                      branch,
                      mainWorkspace.orElse(originPath),
                      mainWorkspace.isPresent());
                });

    // A repository with no backup remote (a greenfield wrapper) has nothing to pull FROM. That is a
    // normal state, not a failure: settle the segment ok and keep walking, since its imported
    // submodule children may well have remotes of their own.
    if (ctx.url() == null || ctx.url().isBlank()) {
      streamLine(process, segmentName, "No backup remote configured — nothing to pull");
      settleOk(process, segmentName);
      return withImportedChildPulls(repoId, "", visited, process, usedSegments);
    }

    try {
      // `--end-of-options`: url and branch are positional, never parsed as flags, so neither a
      // dash-leading url (already rejected at clone) nor branch can smuggle a git flag.
      // Stream the fetch line by line into the segment (live progress on a slow fetch; every line
      // stamps the process's activity clock so a long-but-active fetch can't trip the idle reaper).
      // The other pull verbs below are single-line and stay post-hoc via streamLine.
      String fetchOutput =
          git.exec(
              ctx.workdir().toFile(),
              lineSink(process, segmentName),
              remoteAuth.gitWithCredentials("fetch", "--end-of-options", ctx.url(), ctx.branch()));
      String remoteSha = git.exec(ctx.workdir().toFile(), "git", "rev-parse", "FETCH_HEAD").trim();
      String localSha =
          git.exec(ctx.workdir().toFile(), "git", "rev-parse", "refs/heads/" + ctx.branch()).trim();

      if (remoteSha.equals(localSha) || isAncestor(ctx.workdir(), remoteSha, localSha)) {
        // Already up to date, or local is ahead — nothing to pull; children may still be stale.
        streamLine(
            process,
            segmentName,
            remoteSha.equals(localSha) ? "Already up to date" : "Local branch is ahead of remote");
        settleOk(process, segmentName);
        return withImportedChildPulls(repoId, fetchOutput, visited, process, usedSegments);
      }
      if (isAncestor(ctx.workdir(), localSha, remoteSha)) {
        // Remote is strictly ahead — fast-forward.
        if (ctx.hasMainWorkspace()) {
          // Update the ref and the working tree together (the branch is checked out here).
          git.exec(
              ctx.workdir().toFile(), "git", "merge", "--ff-only", "--end-of-options", remoteSha);
        } else {
          git.exec(
              ctx.workdir().toFile(), "git", "update-ref", "refs/heads/" + ctx.branch(), remoteSha);
        }
        streamLine(process, segmentName, "Fast-forwarded to " + shortSha(remoteSha));
        settleOk(process, segmentName);
        return withImportedChildPulls(repoId, fetchOutput, visited, process, usedSegments);
      }
      // Diverged: merge the remote in when the merge is clean; a conflict parks the remote tip on
      // the merge/<branch>-origin-<branch> branch and throws (the message carries the resolution
      // path).
      String mergeVerdict =
          mergeDivergedRemote(
              ctx.workdir(), ctx.hasMainWorkspace(), ctx.branch(), localSha, remoteSha);
      streamLine(process, segmentName, mergeVerdict);
      settleOk(process, segmentName);
      return withImportedChildPulls(repoId, fetchOutput, visited, process, usedSegments);
    } catch (BadRequestException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git pull failed: " + e.getMessage());
    }
  }

  /**
   * Pulls each imported submodule child after {@code repoId}'s own successful pull, appending their
   * outputs. A child failure (diverged, unreachable remote) degrades loudly, never blocks: the
   * superproject's pull already succeeded, so the child's error becomes a WARNING line in the
   * returned output (for the synchronous callers) and, when streaming, settles the child's segment
   * {@code failed} while the walk continues to the remaining children.
   */
  private String withImportedChildPulls(
      String repoId,
      String ownOutput,
      Set<String> visited,
      TechnicalProcess process,
      Set<String> usedSegments) {
    List<ChildEdge> edges =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    repositorySubmoduleRepository.findByParentId(repoId).stream()
                        .map(e -> new ChildEdge(e.path, e.child.id, e.child.url))
                        .toList());
    StringBuilder output = new StringBuilder(ownOutput.trim());
    for (ChildEdge edge : edges) {
      String childSegment = allocateSegment("pull:" + edge.path(), usedSegments);
      try {
        String childOutput =
            pullRepository(edge.childId(), visited, process, childSegment, usedSegments).trim();
        if (!childOutput.isBlank()) {
          output.append('\n').append(childOutput);
        }
      } catch (Exception e) {
        LOG.warnf(
            e, "Pull of imported submodule '%s' of repository %s failed", edge.path(), repoId);
        output
            .append("\nWARNING: pull of imported submodule '")
            .append(edge.path())
            .append("' (")
            .append(edge.childUrl())
            .append(") failed: ")
            .append(e.getMessage());
        if (process != null) {
          process.appendLine(childSegment, "pull failed: " + e.getMessage());
          // Target the CHILD repo: its remote (possibly a different host than the root) is the one
          // that rejected, so the sign-in must seed the child's credentials.
          settleWithAuthHint(process, childSegment, e.getMessage(), edge.childId());
        }
      }
    }
    return output.toString();
  }

  private static String shortSha(String sha) {
    return sha.length() > 12 ? sha.substring(0, 12) : sha;
  }

  /** The branch a conflicting remote tip is parked on, for {@code branch}. */
  static String mergeBranchName(String branch) {
    return "merge/" + branch + "-origin-" + branch;
  }

  /**
   * Reconciles a diverged {@code branch} (neither {@code localSha} nor {@code remoteSha} is an
   * ancestor of the other) after the remote commits were fetched into {@code workdir}'s object
   * store. A clean three-way merge (probed with {@code git merge-tree --write-tree}, no working
   * tree needed) becomes a real merge commit — local tip as first parent, remote tip as second —
   * and the branch ref advances to it; the returned verdict line describes it. A conflicting merge
   * parks the remote tip on {@code merge/<branch>-origin-<branch>} (created, or overwritten from a
   * previous attempt, so the parked tip always matches the remote's current state) and throws — the
   * message names the conflicting files and the resolution path: merge {@code branch} into the
   * parked branch, resolve, integrate back, then pull/sync/push again.
   */
  private String mergeDivergedRemote(
      Path workdir, boolean checkedOut, String branch, String localSha, String remoteSha)
      throws Exception {
    GitExecutor.ExecResult probe =
        git.execAllowNonZero(
            workdir.toFile(),
            "git",
            "merge-tree",
            "--write-tree",
            "--name-only",
            "--end-of-options",
            localSha,
            remoteSha);
    if (probe.exitCode() == 0) {
      String message = "Merge remote '" + branch + "' into " + branch;
      String mergeSha;
      if (checkedOut) {
        // The branch is checked out here (host-workspace seam): a real `git merge` moves the ref,
        // index and working tree together. The merge-tree probe said clean, so it won't conflict.
        List<String> merge = new ArrayList<>(List.of("git"));
        merge.addAll(gitIdentity.inlineArgs());
        merge.addAll(List.of("merge", "-m", message, "--end-of-options", remoteSha));
        git.exec(workdir.toFile(), gitIdentity.envMap(), merge.toArray(String[]::new));
        mergeSha = git.exec(workdir.toFile(), "git", "rev-parse", "HEAD").trim();
      } else {
        // Bare origin: commit the merged tree directly in the object store and advance the ref.
        String tree = probe.output().lines().findFirst().orElseThrow().trim();
        List<String> commit = new ArrayList<>(List.of("git"));
        commit.addAll(gitIdentity.inlineArgs());
        commit.addAll(List.of("commit-tree", tree, "-p", localSha, "-p", remoteSha, "-m", message));
        mergeSha =
            git.exec(workdir.toFile(), gitIdentity.envMap(), commit.toArray(String[]::new)).trim();
        git.exec(workdir.toFile(), "git", "update-ref", "refs/heads/" + branch, mergeSha);
      }
      return "Merged remote into '" + branch + "' (merge commit " + shortSha(mergeSha) + ")";
    }
    if (probe.exitCode() == 1) {
      String mergeBranch = mergeBranchName(branch);
      git.exec(workdir.toFile(), "git", "update-ref", "refs/heads/" + mergeBranch, remoteSha);
      throw new BadRequestException(
          "Branch '"
              + branch
              + "' conflicts with the remote (conflicting files: "
              + String.join(", ", GitExecutor.conflictedFiles(probe.output()))
              + "); the remote tip was saved to branch '"
              + mergeBranch
              + "' (replacing any previous attempt) — merge '"
              + branch
              + "' into it, resolve the conflicts, integrate it back, then pull, sync or push"
              + " again");
    }
    throw new InternalServerErrorException(
        "Git merge-tree failed [" + probe.exitCode() + "]: " + probe.output());
  }

  /**
   * A segment name for {@code base} unique within {@code usedSegments} (registering it): the plain
   * base, or {@code base (2)}, {@code base (3)}, … when two repos in one walk share a relative
   * path.
   */
  private static String allocateSegment(String base, Set<String> usedSegments) {
    String name = base;
    for (int n = 2; !usedSegments.add(name); n++) {
      name = base + " (" + n + ")";
    }
    return name;
  }

  private static void settleOk(TechnicalProcess process, String segmentName) {
    if (process != null && segmentName != null) {
      process.settleSegment(segmentName, true);
    }
  }

  private static void streamLine(TechnicalProcess process, String segmentName, String line) {
    if (process != null && segmentName != null) {
      process.appendLine(segmentName, line);
    }
  }

  /**
   * A per-line tap that appends each line to the segment as a git command emits it (for {@link
   * GitExecutor#exec(java.io.File, Consumer, String...)}), or {@code null} when there is no process
   * to stream into — so the synchronous callers keep the plain blocking exec.
   */
  private static Consumer<String> lineSink(TechnicalProcess process, String segmentName) {
    return (process == null || segmentName == null)
        ? null
        : line -> process.appendLine(segmentName, line);
  }

  /**
   * Splits captured git output into lines and appends them to the segment (a no-op sans process).
   */
  private static void streamLines(TechnicalProcess process, String segmentName, String output) {
    if (process == null || segmentName == null || output == null || output.isBlank()) {
      return;
    }
    // Default limit drops trailing empty lines (git output usually ends in a newline) while keeping
    // interior blank lines, so a fetch blob doesn't add a stray blank line to the segment.
    for (String line : output.split("\n")) {
      process.appendLine(segmentName, line);
    }
  }

  /**
   * The scalar snapshot a push needs (url, main branch, bare-origin path), read in one short
   * transaction — shared by {@link #pushRepository} and the remote-login sign-in terminal, whose
   * interactive push runs exactly the same command shape in a host-side PTY.
   */
  public record PushSpec(String url, String branch, Path originPath) {}

  /** Reads a {@link PushSpec} in its own short transaction (404 for an unknown id). */
  public PushSpec pushSpec(String repoId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Repository repo = get(repoId);
              Path originPath = requireOrigin(repoId);
              return new PushSpec(repo.url, resolveMainBranch(repo, originPath), originPath);
            });
  }

  /**
   * Pushes the local main branch to the remote. Pushes to the URL directly rather than the "origin"
   * remote, whose {@code mirror=true} config forbids the single-branch refspec.
   *
   * <p>A push the remote rejects as non-fast-forward (the remote gained commits we don't have)
   * doesn't just fail anymore: the remote branch is fetched and reconciled with the same policy the
   * pull uses — remote strictly ahead fast-forwards the mirror (nothing to push), a diverged branch
   * that merges cleanly gets a merge commit and the push is retried once, and a conflicting
   * divergence parks the remote tip on {@code merge/<branch>-origin-<branch>} (see {@link
   * #mergeDivergedRemote}) and fails with the resolution path in the message.
   *
   * <p>Reads its inputs in a short transaction and runs {@code git push} outside it (the pull's
   * {@link PullContext} pattern), so it is safe on a worker thread with no request context — the
   * streamed sync ({@link #beginSyncRepository}) calls it there.
   */
  public String pushRepository(String repoId) {
    PushSpec ctx = pushSpec(repoId);
    // Nothing to push TO: a greenfield wrapper has no backup remote until one is attached. Report
    // it
    // rather than failing — the remote is a backup, so its absence is a configuration state.
    if (ctx.url() == null || ctx.url().isBlank()) {
      return "No backup remote configured — nothing to push";
    }
    try {
      return push(ctx);
    } catch (Exception e) {
      if (!isNonFastForwardRejection(e.getMessage())) {
        throw new InternalServerErrorException("Git push failed: " + e.getMessage());
      }
      return reconcileRejectedPush(repoId, ctx, e.getMessage());
    }
  }

  private String push(PushSpec ctx) throws Exception {
    return git.exec(
        ctx.originPath().toFile(),
        remoteAuth.gitWithCredentials(
            "push", ctx.url(), "refs/heads/" + ctx.branch() + ":refs/heads/" + ctx.branch()));
  }

  /**
   * The remote refused the ref update because it holds commits the mirror doesn't — git's "fetch
   * first"/"non-fast-forward" rejection, as opposed to a hook decline ("remote rejected") or a
   * transport/auth failure, which must surface unchanged.
   */
  private static boolean isNonFastForwardRejection(String message) {
    return message != null
        && (message.contains("non-fast-forward") || message.contains("fetch first"));
  }

  /**
   * The push half of the divergence policy: fetch the remote branch, then fast-forward the mirror
   * when the remote is strictly ahead (nothing local to push), merge-and-retry-once when the
   * histories diverged but merge cleanly, and let {@link #mergeDivergedRemote}'s conflict path park
   * the remote tip and throw otherwise. When the fetched tip turns out NOT to explain the rejection
   * (already contained locally — e.g. a racing pull got there first), the original rejection is
   * surfaced unchanged.
   */
  private String reconcileRejectedPush(String repoId, PushSpec ctx, String rejection) {
    try {
      git.exec(
          ctx.originPath().toFile(),
          remoteAuth.gitWithCredentials(
              "fetch", "--end-of-options", ctx.url(), "refs/heads/" + ctx.branch()));
      String remoteSha =
          git.exec(ctx.originPath().toFile(), "git", "rev-parse", "FETCH_HEAD").trim();
      String localSha =
          git.exec(ctx.originPath().toFile(), "git", "rev-parse", "refs/heads/" + ctx.branch())
              .trim();
      if (remoteSha.equals(localSha) || isAncestor(ctx.originPath(), remoteSha, localSha)) {
        throw new InternalServerErrorException("Git push failed: " + rejection);
      }
      if (isAncestor(ctx.originPath(), localSha, remoteSha)) {
        // The remote simply moved ahead — catch the mirror up instead of failing.
        git.exec(
            ctx.originPath().toFile(),
            "git",
            "update-ref",
            "refs/heads/" + ctx.branch(),
            remoteSha);
        return "Remote is ahead; fast-forwarded '"
            + ctx.branch()
            + "' to "
            + shortSha(remoteSha)
            + " — nothing to push";
      }
      String mergeVerdict =
          mergeDivergedRemote(ctx.originPath(), false, ctx.branch(), localSha, remoteSha);
      String pushOutput = push(ctx);
      return mergeVerdict + "\n" + pushOutput;
    } catch (BadRequestException | InternalServerErrorException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git push failed: " + e.getMessage());
    }
  }

  /**
   * Pull then push the main branch, synchronously. Kept as the throwing, request-thread variant for
   * internal callers; the browser-facing sync endpoint uses the streamed {@link
   * #beginSyncRepository}.
   */
  public String syncRepository(String repoId) {
    String pullOutput = pullRepository(repoId);
    String pushOutput = pushRepository(repoId);
    return (pullOutput + "\n" + pushOutput).trim();
  }

  /**
   * The streamed sync: the {@link #beginPullRepository} walk (one {@code pull:<repo>} segment per
   * repository) followed by a single {@code push:<basename>} segment wrapping {@link
   * #pushRepository}. Registers the {@link TechnicalProcess} before any git runs and returns its id
   * immediately; the browser watches pull-then-push over SSE. Throws 404 in-request for an unknown
   * id.
   *
   * <p>Failure semantics carry over: a diverged/unreachable pull fails the process before the push
   * segment opens ({@link TechnicalProcess#failProvision}); a push failure settles only the {@code
   * push} segment {@code failed} and lets {@code finish()} compute overall {@code done failed}, so
   * a green pull with a red push reads exactly that way.
   *
   * <p>Kind-aware single-flight (see {@link #beginPullRepository}): a live sync is reused; a live
   * <em>pull</em> is a conflict (attaching a sync to a pull would silently skip the push), rejected
   * with a 400 rather than letting a sync report success without pushing.
   */
  public String beginSyncRepository(String repoId) {
    // Validate in-request (unknown id → plain 404, not a process) and derive the url basename
    // shared
    // by the root pull segment and the final push segment.
    String basename = QuarkusTransaction.requiringNew().call(() -> repoLabel(get(repoId)));
    return switch (processes.beginForRepository(repoId, "sync")) {
      case RepoProcessLease.Reused r -> r.processId();
      case RepoProcessLease.Conflict c -> throw repositoryBusy(c.runningKind());
      case RepoProcessLease.Fresh f -> {
        TechnicalProcess process = f.process();
        String rootSegment = "pull:" + basename;
        // Segment names double as the segment key: seed the allocator with the root name so the
        // push
        // segment (and any child pull) can't collide with it.
        Set<String> usedSegments = new HashSet<>();
        usedSegments.add(rootSegment);
        processExecutor.submit(
            () -> {
              try {
                pullRepository(repoId, new HashSet<>(), process, rootSegment, usedSegments);
                // Pull walk done — append the push as its own segment, opened before the push so
                // "now pushing" is visible while it blocks on the network.
                String pushSegment = allocateSegment("push:" + basename, usedSegments);
                process.openSegment(pushSegment);
                try {
                  streamLines(process, pushSegment, pushRepository(repoId));
                  settleOk(process, pushSegment);
                } catch (RuntimeException e) {
                  // A push failure degrades this segment only (not failProvision): the pull
                  // segments
                  // stay green and finish() computes overall `done failed` from the red push
                  // segment.
                  process.appendLine(pushSegment, "push failed: " + e.getMessage());
                  settleWithAuthHint(process, pushSegment, e.getMessage(), repoId);
                }
                process.expectServices(List.of());
                process.finishProvision(true);
              } catch (RuntimeException e) {
                // Root pull failure (diverged branch, unreachable remote): settle the open pull
                // segment failed and emit `done failed` before the push segment ever opens.
                // Idempotent.
                failWithAuthHint(process, e.getMessage(), repoId);
                LOG.debugf(e, "Streamed sync failed for repository %s", repoId);
              }
            });
        yield process.id();
      }
    };
  }

  /**
   * The streamed push: a single {@code push:<basename>} segment wrapping {@link #pushRepository} —
   * {@link #beginSyncRepository} minus the pull walk. Registers the {@link TechnicalProcess} before
   * the push runs and returns its id immediately; a push failure settles the segment {@code failed}
   * with git's message in-stream and {@code finish()} computes overall {@code done failed}. Throws
   * 404 in-request for an unknown id.
   *
   * <p>Kind-aware single-flight (see {@link #beginPullRepository}): a live push is reused; a live
   * pull/sync is a conflict (400), and vice versa — one repo process at a time.
   */
  public String beginPushRepository(String repoId) {
    // Validate in-request (unknown id → plain 404, not a process) and name the sole segment by the
    // repo's url basename, matching the sync's push segment shape.
    String rootSegment =
        QuarkusTransaction.requiringNew().call(() -> "push:" + repoLabel(get(repoId)));
    return switch (processes.beginForRepository(repoId, "push")) {
      case RepoProcessLease.Reused r -> r.processId();
      case RepoProcessLease.Conflict c -> throw repositoryBusy(c.runningKind());
      case RepoProcessLease.Fresh f -> {
        TechnicalProcess process = f.process();
        processExecutor.submit(
            () -> {
              try {
                // Open before the push so "now pushing" is visible while it blocks on the network.
                process.openSegment(rootSegment);
                try {
                  streamLines(process, rootSegment, pushRepository(repoId));
                  settleOk(process, rootSegment);
                } catch (RuntimeException e) {
                  // Degrade the segment only (not failProvision): the red segment carries git's
                  // full message and finish() computes overall `done failed` from it.
                  process.appendLine(rootSegment, "push failed: " + e.getMessage());
                  settleWithAuthHint(process, rootSegment, e.getMessage(), repoId);
                }
                process.expectServices(List.of());
                process.finishProvision(true);
              } catch (RuntimeException e) {
                process.failProvision(e.getMessage());
                LOG.debugf(e, "Streamed push failed for repository %s", repoId);
              }
            });
        yield process.id();
      }
    };
  }

  /** Sets the branch this repository syncs with the remote. The branch must exist locally. */
  @Transactional
  public Repository setMainBranch(String repoId, String branch) {
    Repository repo = get(repoId);
    if (branch == null || branch.isBlank()) {
      throw new BadRequestException("branch is required");
    }
    if (!listBranches(repoId).contains(branch)) {
      throw new BadRequestException("Unknown branch: " + branch);
    }
    repo.mainBranch = branch;
    return repo;
  }

  /**
   * Reports how far the main branch is ahead of / behind the remote, using a read-only {@code git
   * ls-remote} (no objects fetched). Degrades gracefully when the remote is unreachable.
   */
  public SyncStatusDto syncStatus(String repoId) {
    Repository repo = get(repoId);
    Path originPath = requireOrigin(repoId);
    String branch = resolveMainBranch(repo, originPath);

    // No backup remote configured (a greenfield wrapper): the query itself succeeded, there is just
    // no remote branch to compare against. The UI keys its "configure backup remote" affordance off
    // the repository's null url, not off this DTO.
    if (!hasBackupRemote(repo)) {
      return new SyncStatusDto(branch, true, false, null, null);
    }

    String localSha;
    try {
      localSha =
          git.exec(originPath.toFile(), "git", "rev-parse", "--verify", "refs/heads/" + branch)
              .trim();
    } catch (Exception e) {
      // The main branch doesn't exist locally — treat as nothing to report.
      return new SyncStatusDto(branch, true, false, null, null);
    }

    String remoteSha;
    try {
      String out =
          git.exec(
                  originPath.toFile(),
                  remoteAuth.gitWithCredentials("ls-remote", "origin", "refs/heads/" + branch))
              .trim();
      remoteSha = out.isBlank() ? null : out.split("\\s+")[0];
    } catch (Exception e) {
      return new SyncStatusDto(branch, false, false, null, null);
    }

    if (remoteSha == null) {
      return new SyncStatusDto(branch, true, false, null, null);
    }
    if (remoteSha.equals(localSha)) {
      return new SyncStatusDto(branch, true, true, 0, 0);
    }

    // The histories differ. Counting needs the remote commits in the mirror's object store, so
    // fetch them first. Fetch by URL rather than via the "origin" remote: origin is a --mirror,
    // so `git fetch origin` would fast-forward refs/heads/* (a de-facto pull). Fetching the URL
    // populates the objects and FETCH_HEAD while leaving the mirror's branch refs untouched —
    // the same reason pushRepository talks to the URL instead of the mirror remote.
    Integer ahead = null;
    Integer behind = null;
    try {
      // `--end-of-options` forces the URL and refspec to be read as operands, and the
      // `refs/heads/` prefix means a crafted branch name can never start with `-`, so neither
      // can smuggle a git flag (e.g. `--upload-pack=<cmd>`) into the fetch.
      git.exec(
          originPath.toFile(),
          remoteAuth.gitWithCredentials(
              "fetch", "--end-of-options", repo.url, "refs/heads/" + branch));
      String counts =
          git.exec(
                  originPath.toFile(),
                  "git",
                  "rev-list",
                  "--left-right",
                  "--count",
                  remoteSha + "..." + localSha)
              .trim();
      String[] parts = counts.split("\\s+");
      if (parts.length == 2) {
        behind = Integer.parseInt(parts[0]);
        ahead = Integer.parseInt(parts[1]);
      }
    } catch (Exception ignored) {
      // Fetch failed or counts unavailable — leave them null (the UI shows "unknown", not in-sync).
    }
    return new SyncStatusDto(branch, true, true, ahead, behind);
  }

  private Path requireOrigin(String repoId) {
    Path originPath = originPath(repoId);
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }
    return originPath;
  }

  /** True when {@code maybeAncestor} is an ancestor of {@code descendant} (or they are equal). */
  private boolean isAncestor(Path originPath, String maybeAncestor, String descendant) {
    try {
      git.exec(
          originPath.toFile(), "git", "merge-base", "--is-ancestor", maybeAncestor, descendant);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public Repository get(String repoId) {
    return repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
  }

  /** The submodule edges whose superproject is {@code repoId} (its imported child repositories). */
  public List<RepositorySubmodule> listSubmodules(String repoId) {
    get(repoId); // verify the repository exists
    return repositorySubmoduleRepository.findByParentId(repoId);
  }

  public List<String> listBranches(String repoId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }

    try {
      String output = git.exec(originPath.toFile(), "git", "branch", "--format=%(refname:short)");
      return output.lines().map(String::trim).filter(b -> !b.isBlank()).toList();
    } catch (Exception e) {
      throw new InternalServerErrorException("Git branch listing failed: " + e.getMessage());
    }
  }

  /**
   * The repository's branches, each tagged with whether it can be safely cleaned up (see {@link
   * WorkspaceService#canCleanupBranch}). Used by the branch list UI to offer cleanup in place of
   * integrate once a branch is fully merged.
   */
  public List<BranchDto> listBranchesWithCleanup(String repoId) {
    Repository repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Path originPath = Path.of(dataDir, repoId, "origin");
    return listBranches(repoId).stream()
        .map(
            b -> {
              var summary = workspaceService.summarize(repoId, originPath, b, repo.mainBranch);
              return new BranchDto(
                  b,
                  workspaceService.canCleanupBranch(repoId, originPath, b, repo.mainBranch),
                  summary.parent(),
                  summary.ahead(),
                  summary.behind());
            })
        .toList();
  }

  /**
   * Deletes a git branch from the repository's origin. Refuses to delete a branch that is the
   * {@code parent} of any workspace, since that would orphan those workspaces in the branch tree.
   */
  @Transactional
  public void deleteBranch(String repoId, String branch) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    // `branch` is user-supplied: reject blank or dash-leading names so a value like
    // "-D"/"--force" can't be smuggled to git as a flag (argv flag injection).
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name: " + branch);
    }

    boolean hasChildren =
        workspaceRepository.findActiveByRepositoryId(repoId).stream()
            .anyMatch(wt -> branch.equals(wt.parent));
    if (hasChildren) {
      throw new BadRequestException("Branch has child workspaces: " + branch);
    }

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }

    try {
      // `--` terminates option parsing so the branch name is always treated as a ref.
      git.exec(originPath.toFile(), "git", "branch", "-D", "--", branch);
    } catch (Exception e) {
      throw new InternalServerErrorException("Git branch delete failed: " + e.getMessage());
    }
  }

  /**
   * Deletes a repository, <b>refusing the project's wrapper</b>: the wrapper is the project root
   * and goes with the project, not on its own. {@code ProjectService.delete} tears it down through
   * {@link #deleteInternal}.
   */
  @Transactional
  public void delete(String repoId) {
    Repository repo = get(repoId);
    if (repo.archetype == RepositoryArchetype.PROJECT) {
      throw new BadRequestException(
          "This is the project's wrapper repository — the project root — and cannot be deleted on"
              + " its own; delete the project instead.");
    }
    deleteInternal(repoId);
  }

  /** {@link #delete} without the wrapper guard — the path a project deletion takes. */
  @Transactional
  public void deleteInternal(String repoId) {
    Repository repo = get(repoId);
    // Delete the whole footprint, not just the DB row: otherwise every delete (and every seed
    // reset, which deletes then recreates) leaks the repo's workspace containers, their persistent
    // /workspace volumes, and its on-disk clone directory as orphans. DB rows for
    // workspaces/commands/events/services cascade off the repository row deletion below.
    for (ContainerRuntime.ContainerInfo info : containerRuntime.listWorkspaceContainers(repoId)) {
      try {
        containerRuntime.rm(info.name());
      } catch (RuntimeException e) {
        LOG.warnf(
            "Failed to remove container %s while deleting repository %s: %s",
            info.name(), repoId, e.getMessage());
      }
    }
    // Remove the per-workspace /workspace volumes AFTER their containers are gone (docker refuses
    // an
    // in-use volume). Sweep the managed-volume list so containerless/orphaned volumes are reaped
    // too
    // — a stopped-then-removed container leaves only its volume behind. Best-effort.
    for (ContainerRuntime.VolumeInfo vol : containerRuntime.listWorkspaceVolumes()) {
      if (repoId.equals(vol.repoId())) {
        try {
          containerRuntime.removeWorkspaceVolume(vol.workspaceId());
        } catch (RuntimeException e) {
          LOG.warnf(
              "Failed to remove workspace volume for %s while deleting repository %s: %s",
              vol.workspaceId(), repoId, e.getMessage());
        }
      }
    }
    deleteDataDir(repoId);
    // The rows referencing this repository (workspaces and their events, name aliases, commands)
    // go by the schema's `on delete cascade`, not one by one here. That is correct as long as each
    // service call owns its transaction, which every caller does. A caller that instead CREATED
    // this repository earlier in the SAME transaction would still hold those children managed, and
    // Hibernate would flush a child pointing at a removed parent — so don't do that; give the
    // create and the delete their own transactions, as production always does.
    repositoryRepository.delete(repo);
  }

  /** Recursively remove {@code <data-dir>/<repoId>} (bare origin + any transient merge scratch). */
  private void deleteDataDir(String repoId) {
    deleteRecursively(Path.of(dataDir, repoId));
  }

  /** Best-effort recursive delete — children before parents. */
  private void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  LOG.warnf("Failed to delete %s: %s", p, e.getMessage());
                }
              });
    } catch (IOException e) {
      LOG.warnf("Failed to remove directory %s: %s", dir, e.getMessage());
    }
  }
}
