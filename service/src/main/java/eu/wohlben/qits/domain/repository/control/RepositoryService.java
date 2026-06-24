package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.repository.dto.SyncStatusDto;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorktreeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class RepositoryService {

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorktreeRepository worktreeRepository;

  @Inject MetadataService metadataService;

  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @Transactional
  public Repository cloneRepository(String url, RepositoryArchetype archetype, Project project) {
    if (url == null || url.isBlank()) {
      throw new BadRequestException("url is required");
    }

    Repository repo = new Repository();
    repo.id = UUID.randomUUID().toString();
    repo.url = url.trim();
    repo.archetype = archetype != null ? archetype : RepositoryArchetype.SERVICE;
    repo.project = project;
    repositoryRepository.persist(repo);

    Path originPath = Path.of(dataDir, repo.id, "origin");
    try {
      Files.createDirectories(originPath.getParent());
      git.exec(null, "git", "clone", "--mirror", repo.url, originPath.toString());
    } catch (Exception e) {
      throw new InternalServerErrorException("Git clone failed: " + e.getMessage());
    }

    // The main branch defaults to the remote's default branch (the mirror's HEAD).
    repo.mainBranch = detectDefaultBranch(originPath);

    metadataService.writeRepositoryMetadata(repo);

    return repo;
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

    try {
      String fetchOutput = git.exec(originPath.toFile(), "git", "fetch", "origin", branch);
      String remoteSha = git.exec(originPath.toFile(), "git", "rev-parse", "FETCH_HEAD").trim();
      String localSha =
          git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/" + branch).trim();

      if (remoteSha.equals(localSha) || isAncestor(originPath, remoteSha, localSha)) {
        // Already up to date, or local is ahead — nothing to pull.
        return fetchOutput;
      }
      if (isAncestor(originPath, localSha, remoteSha)) {
        // Remote is strictly ahead — fast-forward the local branch.
        git.exec(originPath.toFile(), "git", "update-ref", "refs/heads/" + branch, remoteSha);
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

    // The histories differ. Exact counts need the remote commits locally, which a read-only
    // ls-remote doesn't fetch, so they may be null until the next pull.
    Integer ahead = null;
    Integer behind = null;
    try {
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
      // Remote commits not present locally — leave counts null.
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
   * Deletes a git branch from the repository's origin. Refuses to delete a branch that is the
   * {@code parent} of any worktree, since that would orphan those worktrees in the branch tree.
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
        worktreeRepository.findByRepositoryId(repoId).stream()
            .anyMatch(wt -> branch.equals(wt.parent));
    if (hasChildren) {
      throw new BadRequestException("Branch has child worktrees: " + branch);
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
    repositoryRepository.delete(repo);
  }
}
