package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.repository.dto.CommitDto;
import eu.wohlben.qits.domain.repository.dto.CommitLogDto;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.Worktree;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorktreeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reads the commit log for a branch, scoped to the commits unique to it. The "parent" a branch is
 * compared against is the parent of the worktree that owns the branch, falling back to the
 * repository's main branch when the branch isn't worktree-backed.
 */
@ApplicationScoped
public class CommitService {

  /** Field separator between log fields ({@code %x1f}); never appears in any single field. */
  private static final String FIELD_SEP = "\u001f";

  private static final String LOG_FORMAT = "--format=%H%x1f%h%x1f%an%x1f%ae%x1f%cI%x1f%s";

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorktreeRepository worktreeRepository;

  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  /**
   * Lists the commits on {@code branch} that are not on its parent ({@code git log
   * parent..branch}). When the parent can't be resolved (or equals the branch) the full branch
   * history is returned instead, so the view degrades gracefully rather than erroring.
   */
  public CommitLogDto listCommits(String repoId, String branch) {
    // `branch` is user-supplied: reject blank or dash-leading names so a value like
    // "-D"/"--all" can't be smuggled to git as a flag (argv flag injection).
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name: " + branch);
    }

    Repository repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }

    String parent = resolveParent(repoId, repo, branch);
    boolean usableParent =
        parent != null && !parent.isBlank() && !parent.startsWith("-") && !parent.equals(branch);
    String range = usableParent ? parent + ".." + branch : branch;

    try {
      // `--` terminates option parsing so the refspec is never read as a flag.
      String output = git.exec(originPath.toFile(), "git", "log", LOG_FORMAT, range, "--");
      return new CommitLogDto(branch, usableParent ? parent : null, parseCommits(output));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git log failed: " + e.getMessage());
    }
  }

  /**
   * The branch a worktree forked from, when {@code branch} is owned by a worktree; otherwise the
   * repository's main branch. A worktree's branch is resolved from disk (the worktree's checkout),
   * matching how {@link WorktreeService} reports it.
   */
  private String resolveParent(String repoId, Repository repo, String branch) {
    for (Worktree wt : worktreeRepository.findByRepositoryId(repoId)) {
      Path worktreePath = Path.of(dataDir, repoId, "worktrees", wt.worktreeId);
      if (!Files.exists(worktreePath)) {
        continue;
      }
      try {
        if (branch.equals(git.getCurrentBranch(worktreePath))) {
          return wt.parent;
        }
      } catch (Exception ignored) {
        // Worktree checkout unreadable — skip it.
      }
    }
    return repo.mainBranch;
  }

  private List<CommitDto> parseCommits(String output) {
    List<CommitDto> commits = new ArrayList<>();
    for (String line : output.split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      // Keep trailing empties (-1) so an empty commit message still yields a 6th field.
      String[] f = line.split(FIELD_SEP, -1);
      if (f.length != 6) {
        continue;
      }
      commits.add(new CommitDto(f[0], f[1], f[2], f[3], f[4], f[5]));
    }
    return commits;
  }
}
