package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.InternalServerErrorException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.featureflow.control.FeatureFlowPhaseActionService;
import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.repository.dto.BranchDto;
import eu.wohlben.qits.domain.repository.dto.SyncStatusDto;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.entity.RepositorySubmodule;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.RepositorySubmoduleRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RepositoryService {

  private static final Logger LOG = Logger.getLogger(RepositoryService.class);

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject MetadataService metadataService;

  @Inject WorkspaceService workspaceService;

  @Inject ContainerRuntime containerRuntime;

  @Inject GitExecutor git;

  @Inject GitSubmoduleParser submoduleParser;

  @Inject RepositorySubmoduleRepository repositorySubmoduleRepository;

  @Inject FeatureFlowPhaseActionService featureFlowPhaseActionService;

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  /**
   * A backstop against pathological submodule chains; deduplication already terminates real ones.
   */
  private static final int MAX_SUBMODULE_DEPTH = 10;

  @Transactional
  public Repository cloneRepository(String url, RepositoryArchetype archetype, Project project) {
    return cloneRepository(url, archetype, project, new HashSet<>(), 0, true);
  }

  /**
   * The recursive workhorse behind {@link #cloneRepository(String, RepositoryArchetype, Project)}.
   * Clones the repository, then imports each of its {@code .gitmodules} submodules as a sibling
   * repository under the same project (deduplicated by resolved url) and links them with a {@link
   * RepositorySubmodule} edge. Runs entirely within the caller's single transaction — children join
   * it, so a mid-tree failure rolls the whole import back (leaving at most orphan on-disk clones,
   * cleaned on retry, the same failure mode as a single clone).
   *
   * @param createMainWorkspace whether to give the repository its default main-branch workspace —
   *     {@code true} for the top-level superproject, {@code false} for an imported child (a
   *     submodule materializes inside its superproject's container, not as an independent sibling
   *     workspace; the child stays usable standalone since {@code createMainWorkspace} is
   *     idempotent).
   * @param visitedUrls resolved urls already on the current import path (cycle backstop).
   * @param depth recursion depth ({@link #MAX_SUBMODULE_DEPTH} backstop).
   */
  private Repository cloneRepository(
      String url,
      RepositoryArchetype archetype,
      Project project,
      Set<String> visitedUrls,
      int depth,
      boolean createMainWorkspace) {
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

    Repository repo = new Repository();
    repo.id = UUID.randomUUID().toString();
    repo.url = trimmedUrl;
    repo.archetype = archetype != null ? archetype : RepositoryArchetype.SERVICE;
    repo.project = project;
    repositoryRepository.persist(repo);

    Path originPath = Path.of(dataDir, repo.id, "origin");
    try {
      Files.createDirectories(originPath.getParent());
      git.exec(
          null, "git", "clone", "--mirror", "--end-of-options", repo.url, originPath.toString());
    } catch (Exception e) {
      throw new InternalServerErrorException("Git clone failed: " + e.getMessage());
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

    importSubmodules(repo, project, originPath, visitedUrls, depth);

    return repo;
  }

  /**
   * Imports {@code repo}'s {@code .gitmodules} submodules as sibling repositories under {@code
   * project}, deduplicated by resolved url within the project, each linked by a {@link
   * RepositorySubmodule} edge. A submodule-free repository (no {@code .gitmodules}) reads an empty
   * list here, so this is a strict no-op for it.
   */
  private void importSubmodules(
      Repository repo, Project project, Path originPath, Set<String> visitedUrls, int depth) {
    if (depth >= MAX_SUBMODULE_DEPTH) {
      LOG.warnf(
          "Reached max submodule depth %d at %s; not recursing further",
          MAX_SUBMODULE_DEPTH, repo.url);
      return;
    }
    visitedUrls.add(normalizeUrl(repo.url));

    for (GitSubmoduleParser.Submodule sub :
        submoduleParser.readSubmodules(originPath.toFile(), repo.mainBranch)) {
      String childUrl = submoduleParser.resolveSubmoduleUrl(repo.url, sub.url());

      // Dedup within the project: reuse an existing sibling with the same url (the diamond case),
      // else clone-and-recurse. Panache auto-flushes before this query, so a child imported earlier
      // in this same transaction is already visible.
      Repository child = repositoryRepository.findByUrlInProject(childUrl, project.id).orElse(null);
      if (child == null) {
        if (visitedUrls.contains(normalizeUrl(childUrl))) {
          // Cycle backstop: url is on the current import path but not yet a persisted row.
          continue;
        }
        child =
            cloneRepository(
                childUrl, RepositoryArchetype.SERVICE, project, visitedUrls, depth + 1, false);
      }

      if (!repositorySubmoduleRepository.existsByParentAndPath(repo.id, sub.path())) {
        RepositorySubmodule edge = new RepositorySubmodule();
        edge.parent = repo;
        edge.child = child;
        edge.path = sub.path();
        edge.name = sub.name();
        repositorySubmoduleRepository.persist(edge);
      }
    }
  }

  /** Normalizes a url for cycle detection: trims and drops a single trailing slash. */
  private String normalizeUrl(String url) {
    String u = url.trim();
    return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
  }

  private Path originPath(String repoId) {
    return Path.of(dataDir, repoId, "origin");
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
   * local ref only when the remote is strictly ahead; a no-op when already up to date or locally
   * ahead, and an error when the histories have diverged (manual merge needed).
   */
  public String pullRepository(String repoId) {
    Repository repo = get(repoId);
    Path originPath = requireOrigin(repoId);
    String branch = resolveMainBranch(repo, originPath);

    // The main branch lives in its default workspace, so pull there: git refuses to fetch-update a
    // ref that is checked out, and updating it behind the workspace's back would desync the working
    // tree. We fetch by URL (into FETCH_HEAD, not refs/heads/*) and fast-forward the workspace,
    // which
    // moves the ref and the checkout together. Fall back to the bare origin for a repo with no main
    // workspace.
    Optional<Path> mainWorkspace = workspaceService.workspacePathForBranch(repoId, branch);
    Path workdir = mainWorkspace.orElse(originPath);

    try {
      // `--end-of-options`: url and branch are positional, never parsed as flags, so neither a
      // dash-leading url (already rejected at clone) nor branch can smuggle a git flag.
      String fetchOutput =
          git.exec(workdir.toFile(), "git", "fetch", "--end-of-options", repo.url, branch);
      String remoteSha = git.exec(workdir.toFile(), "git", "rev-parse", "FETCH_HEAD").trim();
      String localSha =
          git.exec(workdir.toFile(), "git", "rev-parse", "refs/heads/" + branch).trim();

      if (remoteSha.equals(localSha) || isAncestor(workdir, remoteSha, localSha)) {
        // Already up to date, or local is ahead — nothing to pull.
        return fetchOutput;
      }
      if (isAncestor(workdir, localSha, remoteSha)) {
        // Remote is strictly ahead — fast-forward.
        if (mainWorkspace.isPresent()) {
          // Update the ref and the working tree together (the branch is checked out here).
          git.exec(workdir.toFile(), "git", "merge", "--ff-only", "--end-of-options", remoteSha);
        } else {
          git.exec(workdir.toFile(), "git", "update-ref", "refs/heads/" + branch, remoteSha);
        }
        return fetchOutput;
      }
      throw new BadRequestException(
          "Branch '" + branch + "' has diverged from the remote; manual merge required");
    } catch (BadRequestException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git pull failed: " + e.getMessage());
    }
  }

  /**
   * Pushes the local main branch to the remote. Pushes to the URL directly rather than the "origin"
   * remote, whose {@code mirror=true} config forbids the single-branch refspec.
   */
  public String pushRepository(String repoId) {
    Repository repo = get(repoId);
    Path originPath = requireOrigin(repoId);
    String branch = resolveMainBranch(repo, originPath);

    try {
      return git.exec(
          originPath.toFile(),
          "git",
          "push",
          repo.url,
          "refs/heads/" + branch + ":refs/heads/" + branch);
    } catch (Exception e) {
      throw new InternalServerErrorException("Git push failed: " + e.getMessage());
    }
  }

  /** Pull then push the main branch. */
  public String syncRepository(String repoId) {
    String pullOutput = pullRepository(repoId);
    String pushOutput = pushRepository(repoId);
    return (pullOutput + "\n" + pushOutput).trim();
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
          git.exec(originPath.toFile(), "git", "ls-remote", "origin", "refs/heads/" + branch)
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
          "git",
          "fetch",
          "--end-of-options",
          repo.url,
          "refs/heads/" + branch);
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

  @Transactional
  public void delete(String repoId) {
    Repository repo = get(repoId);
    // Delete the whole footprint, not just the DB row: otherwise every delete (and every seed
    // reset, which deletes then recreates) leaks the repo's workspace containers and its on-disk
    // clone directory as orphans. DB rows for workspaces/commands/events/daemons cascade off the
    // repository row deletion below.
    for (ContainerRuntime.ContainerInfo info : containerRuntime.listWorkspaceContainers(repoId)) {
      try {
        containerRuntime.rm(info.name());
      } catch (RuntimeException e) {
        LOG.warnf(
            "Failed to remove container %s while deleting repository %s: %s",
            info.name(), repoId, e.getMessage());
      }
    }
    deleteDataDir(repoId);
    // The repository row cascade-deletes its own actions; unbind them from any feature flow first
    // (the phase-action FK has no cascade, so a still-bound action would fail the delete).
    featureFlowPhaseActionService.deleteBindingsForRepository(repoId);
    repositoryRepository.delete(repo);
  }

  /** Recursively remove {@code <data-dir>/<repoId>} (bare origin + any transient merge scratch). */
  private void deleteDataDir(String repoId) {
    Path repoDir = Path.of(dataDir, repoId);
    if (!Files.exists(repoDir)) {
      return;
    }
    try (var paths = Files.walk(repoDir)) {
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
      LOG.warnf("Failed to remove data dir for repository %s: %s", repoId, e.getMessage());
    }
  }
}
