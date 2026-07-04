package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.InternalServerErrorException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.repository.dto.CommitChangesDto;
import eu.wohlben.qits.domain.repository.dto.CommitDto;
import eu.wohlben.qits.domain.repository.dto.CommitFileChangeDto;
import eu.wohlben.qits.domain.repository.dto.CommitFileDiffDto;
import eu.wohlben.qits.domain.repository.dto.CommitLogDto;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.Worktree;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorktreeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

  /**
   * Record separator ({@code %x1e}) prefixing each commit, so the per-commit file lists that {@code
   * --name-only} appends (each on its own line) can be split back apart.
   */
  private static final String RECORD_SEP = "\u001e";

  private static final String LOG_FORMAT = "--format=%x1e%H%x1f%h%x1f%an%x1f%ae%x1f%cI%x1f%s";

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
      String output =
          git.exec(originPath.toFile(), "git", "log", "--name-only", LOG_FORMAT, range, "--");
      return new CommitLogDto(branch, usableParent ? parent : null, parseCommits(output));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git log failed: " + e.getMessage());
    }
  }

  /**
   * Lists the commits a fast-forward or merge would bring into a worktree's branch from its parent
   * — the commits the parent has that the branch doesn't yet ({@code git log branch..parent}),
   * newest first. Returns an empty list when the worktree has no resolvable parent, is already up
   * to date, or the refs can't be read. Used for the "commits about to be pulled in" hover popover.
   */
  public CommitLogDto listIncomingCommits(String repoId, String worktreeId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    Worktree worktree =
        worktreeRepository
            .findActiveByRepositoryAndWorktreeId(repoId, worktreeId)
            .orElseThrow(() -> new NotFoundException("Worktree not found: " + worktreeId));

    Path originPath = requireOrigin(repoId);

    // The branch is the worktree's stored column (there is no host checkout to read it from — the
    // checkout lives in the container). The log range below runs against the bare origin, which
    // holds every worktree branch as a ref.
    String branch = worktree.branch;
    String parent = worktree.parent;
    boolean usable =
        branch != null
            && !branch.isBlank()
            && !branch.startsWith("-")
            && parent != null
            && !parent.isBlank()
            && !parent.startsWith("-")
            && !parent.equals(branch);
    if (!usable) {
      return new CommitLogDto(branch, null, List.of());
    }

    // `branch..parent` = commits reachable from the parent but not the branch — exactly what a
    // fast-forward (or merge) would add. `--` terminates options so the range can't be read as
    // flag.
    String range = branch + ".." + parent;
    try {
      String output =
          git.exec(originPath.toFile(), "git", "log", "--name-only", LOG_FORMAT, range, "--");
      return new CommitLogDto(branch, parent, parseCommits(output));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git log failed: " + e.getMessage());
    }
  }

  /**
   * Lists the files a commit changed relative to its diff base. The base is the explicit {@code
   * parent} when given, otherwise the commit's own first parent ({@code --root} so a root commit
   * still reports its added files). {@code parent} in the result is the resolved base ({@code null}
   * for a root commit).
   */
  public CommitChangesDto listChanges(String repoId, String commit, String parent) {
    requireRef(commit, "commit");
    String base = normalizeParent(parent);
    Path originPath = requireOrigin(repoId);

    List<String> cmd =
        new ArrayList<>(List.of("git", "diff-tree", "-r", "--no-commit-id", "--name-status", "-M"));
    if (base != null) {
      cmd.add(base);
    } else {
      cmd.add("--root");
    }
    cmd.add(commit);
    cmd.add("--"); // terminate options so refs/paths can't be read as flags

    try {
      String output = git.exec(originPath.toFile(), cmd.toArray(String[]::new));
      return new CommitChangesDto(commit, base, parseChanges(output));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git diff-tree failed: " + e.getMessage());
    }
  }

  /**
   * The unified diff of a single {@code path} in {@code commit}, relative to the same base as
   * {@link #listChanges}. The diff text is empty when the file has no textual change (binary or
   * pure rename).
   */
  public CommitFileDiffDto getFileDiff(String repoId, String commit, String parent, String path) {
    requireRef(commit, "commit");
    if (path == null || path.isBlank() || path.startsWith("-")) {
      throw new BadRequestException("Invalid path: " + path);
    }
    String base = normalizeParent(parent);
    Path originPath = requireOrigin(repoId);

    List<String> cmd = new ArrayList<>(List.of("git", "diff-tree", "-p", "-M", "--no-commit-id"));
    if (base != null) {
      cmd.add(base);
    } else {
      cmd.add("--root");
    }
    cmd.add(commit);
    cmd.add("--");
    cmd.add(path);

    try {
      String diff = git.exec(originPath.toFile(), cmd.toArray(String[]::new));
      return new CommitFileDiffDto(path, changeTypeFromPatch(diff), diff);
    } catch (Exception e) {
      throw new InternalServerErrorException("Git diff-tree failed: " + e.getMessage());
    }
  }

  /**
   * Rejects null/blank/dash-leading refs so a value like {@code -D} can't be smuggled as a flag.
   */
  private void requireRef(String ref, String name) {
    if (ref == null || ref.isBlank() || ref.startsWith("-")) {
      throw new BadRequestException("Invalid " + name + ": " + ref);
    }
  }

  /** Null/blank parent means "diff against the commit's first parent"; otherwise validate it. */
  private String normalizeParent(String parent) {
    if (parent == null || parent.isBlank()) {
      return null;
    }
    if (parent.startsWith("-")) {
      throw new BadRequestException("Invalid parent: " + parent);
    }
    return parent;
  }

  private Path requireOrigin(String repoId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }
    return originPath;
  }

  private List<CommitFileChangeDto> parseChanges(String output) {
    List<CommitFileChangeDto> files = new ArrayList<>();
    for (String line : output.split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      // name-status: "<STATUS>\t<path>" or, for renames/copies, "<STATUS>\t<old>\t<new>".
      String[] f = line.split("\t");
      if (f.length < 2) {
        continue;
      }
      char code = f[0].charAt(0);
      String changeType = changeType(code);
      if ((code == 'R' || code == 'C') && f.length >= 3) {
        files.add(new CommitFileChangeDto(f[2], f[1], changeType));
      } else {
        files.add(new CommitFileChangeDto(f[1], null, changeType));
      }
    }
    return files;
  }

  private String changeType(char code) {
    return switch (code) {
      case 'A' -> "ADDED";
      case 'D' -> "DELETED";
      case 'R' -> "RENAMED";
      case 'C' -> "COPIED";
      case 'T' -> "TYPE_CHANGED";
      default -> "MODIFIED";
    };
  }

  /** Derives the change type from the patch header, avoiding a second git call. */
  private String changeTypeFromPatch(String diff) {
    if (diff == null || diff.isBlank()) {
      return "MODIFIED";
    }
    if (diff.contains("new file mode")) {
      return "ADDED";
    }
    if (diff.contains("deleted file mode")) {
      return "DELETED";
    }
    if (diff.contains("rename from ")) {
      return "RENAMED";
    }
    if (diff.contains("copy from ")) {
      return "COPIED";
    }
    return "MODIFIED";
  }

  /**
   * The branch a worktree forked from, when {@code branch} is owned by a worktree; otherwise the
   * repository's main branch. Matched against each worktree's stored {@code branch} column (the
   * checkout lives in the container now — there is no host path to read the branch from).
   */
  private String resolveParent(String repoId, Repository repo, String branch) {
    for (Worktree wt : worktreeRepository.findActiveByRepositoryId(repoId)) {
      if (branch.equals(wt.branch)) {
        return wt.parent;
      }
    }
    return repo.mainBranch;
  }

  /**
   * Parses {@code git log --name-only} output: each commit is prefixed with {@link #RECORD_SEP},
   * its first line holds the {@link #FIELD_SEP}-separated fields, and the remaining non-blank lines
   * are the paths it changed (absent for merge commits).
   */
  private List<CommitDto> parseCommits(String output) {
    List<CommitDto> commits = new ArrayList<>();
    for (String block : output.split(RECORD_SEP)) {
      if (block.isBlank()) {
        continue;
      }
      String[] lines = block.split("\n", -1);
      // Keep trailing empties (-1) so an empty commit message still yields a 6th field.
      String[] f = lines[0].split(FIELD_SEP, -1);
      if (f.length != 6) {
        continue;
      }
      List<String> files = new ArrayList<>();
      for (int i = 1; i < lines.length; i++) {
        if (!lines[i].isBlank()) {
          files.add(lines[i]);
        }
      }
      commits.add(new CommitDto(f[0], f[1], f[2], f[3], f[4], f[5], files));
    }
    return commits;
  }
}
