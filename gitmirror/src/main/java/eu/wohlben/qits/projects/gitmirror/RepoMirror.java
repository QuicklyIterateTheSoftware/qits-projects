package eu.wohlben.qits.projects.gitmirror;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One repository's local mirror, and every git operation this service performs on it.
 *
 * <p>Four kinds of call live here and the distinction is the whole design:
 *
 * <ul>
 *   <li><b>Wire reads</b> — {@link #remoteBranchSha}, {@link #remoteBranches}. {@code ls-remote}
 *       against the git host: authoritative, no objects transferred, and correct even when the
 *       mirror has never been fetched.
 *   <li><b>Local reads</b> — {@link #isAncestor}, {@link #aheadBehind}, {@link #previewMerge}. They
 *       need objects, so they run in the mirror and the caller refreshes it first. A slightly stale
 *       answer is a slightly stale number on a screen; that is the whole exposure.
 *   <li><b>Plumbing</b> — {@link #writeTree} and {@link #commitTree}. No working tree at all: this
 *       service only ever manufactures a commit with no checkout involved (a project template's
 *       root commit, a diverged pull's merge commit), so there is no worktree in this module.
 *   <li><b>Writes</b> — {@link #push} and nothing else. Every ref this service moves is moved by a
 *       push, which is the property the whole module exists to establish.
 * </ul>
 *
 * <p>Two remotes, never confused. The git host — {@link GitRemotes#fetchUrl}/{@link
 * GitRemotes#pushUrl} — is deployment knowledge and tokenless; every fetch, {@code ls-remote} and
 * push here goes to it with no credentials attached. A repository's own backup remote (an external
 * forge) is row state and needs auth, so it is never named by {@link GitRemotes}: {@link
 * #cloneFrom} and {@link #fetchIntoFetchHead} take its url and a {@link GitCredentials} as explicit
 * arguments instead.
 */
public final class RepoMirror {

  private final GitCli cli;
  private final GitRemotes remotes;
  private final String repoId;
  private final Path gitDir;
  private final Path skeletonRoot;
  private final Duration networkTimeout;
  private final Duration freshness;

  private final ReentrantLock fetchLock = new ReentrantLock();
  private volatile long fetchedAtMillis = 0L;

  RepoMirror(
      GitCli cli,
      GitRemotes remotes,
      String repoId,
      Path root,
      Duration networkTimeout,
      Duration freshness) {
    this.cli = cli;
    this.remotes = remotes;
    this.repoId = repoId;
    this.gitDir = root.resolve("mirrors").resolve(repoId + ".git");
    this.skeletonRoot = root.resolve("skeleton").resolve(repoId);
    this.networkTimeout = networkTimeout;
    this.freshness = freshness;
  }

  public String repoId() {
    return repoId;
  }

  /** The mirror's bare git directory. */
  public Path gitDir() {
    return gitDir;
  }

  // -----------------------------------------------------------------------------------------
  // the mirror's lifecycle
  // -----------------------------------------------------------------------------------------

  /** Fetch when the mirror is older than the freshness window; clone it first if it is absent. */
  public void refresh() {
    if (Files.isDirectory(gitDir)
        && System.currentTimeMillis() - fetchedAtMillis < freshness.toMillis()) {
      return;
    }
    refreshNow();
  }

  /**
   * Fetch unconditionally, cloning first if the mirror is absent — what every flow that is about to
   * <em>write</em> calls, because a preflight against a stale object store is a preflight against
   * the wrong repository.
   */
  public void refreshNow() {
    fetchLock.lock();
    try {
      if (!Files.isDirectory(gitDir)) {
        cloneMirror();
      } else {
        fetch();
      }
      fetchedAtMillis = System.currentTimeMillis();
    } finally {
      fetchLock.unlock();
    }
  }

  /**
   * Mark the mirror stale, so the next {@link #refresh()} fetches whatever the freshness window
   * would otherwise have let it skip. Called after every accepted push: the git host has just moved
   * a ref this mirror cannot know about.
   */
  public void markStale() {
    fetchedAtMillis = 0L;
  }

  private void cloneMirror() {
    createMirrorDirectory();
    // --mirror rather than --bare: it sets refs/*:refs/* as the fetch refspec, so one `git fetch
    // --prune` below keeps every branch identical to the host's.
    GitCli.Result result =
        wire(
            "Could not clone the mirror of " + repoId,
            null,
            "git",
            "clone",
            "--mirror",
            "--quiet",
            remotes.fetchUrl(repoId),
            gitDir.toString());
    if (result.exitCode() != 0) {
      // A half-written directory would make the next attempt take the fetch branch and fail
      // differently, which is a worse error than this one.
      deleteQuietly(gitDir);
      throw new GitMirrorException(
          "Could not clone the mirror of " + repoId + ": " + result.output());
    }
  }

  private void fetch() {
    GitCli.Result result =
        wire(
            "Could not fetch the mirror of " + repoId,
            gitDir,
            "git",
            "fetch",
            "--prune",
            "--quiet",
            remotes.fetchUrl(repoId),
            "+refs/*:refs/*");
    if (result.exitCode() != 0) {
      throw new GitMirrorException(
          "Could not fetch the mirror of " + repoId + ": " + result.output());
    }
  }

  /**
   * {@code git init --bare}, with {@code HEAD} already pointed at {@code defaultBranch} — the
   * greenfield creation path ({@code initWrapperOrigin}), where there is no upstream to clone from
   * and the mirror is the origin of the repository's very first commit.
   *
   * <p>{@code git init -b <branch>} folds what used to be two calls against the served bare
   * (plain {@code init --bare}, then a separate {@code symbolic-ref HEAD}) into one, so there is no
   * window where the mirror exists with an arbitrary default branch name.
   */
  public void initEmpty(String defaultBranch) {
    requireRefName("defaultBranch", defaultBranch);
    createMirrorDirectory();
    GitCli.Result result =
        unbounded(
            null,
            Map.of(),
            "git",
            "init",
            "--bare",
            "--quiet",
            "-b",
            defaultBranch,
            "--end-of-options",
            gitDir.toString());
    if (result.exitCode() != 0) {
      throw new GitMirrorException(
          "Could not initialize an empty mirror for " + repoId + ": " + result.output());
    }
  }

  /**
   * Seeds the mirror by cloning {@code upstreamUrl} wholesale ({@code git clone --mirror}) — the
   * import path ({@code cloneOne}), landing in this module's own tree rather than on the shared
   * volume. The one call this module makes against a remote it does not control the credentials
   * for, so it takes them explicitly rather than assuming the tokenless git-host convention {@link
   * #cloneMirror} relies on.
   */
  public void cloneFrom(String upstreamUrl, GitCredentials credentials) {
    requireUrl("upstreamUrl", upstreamUrl);
    if (credentials == null) {
      throw new GitMirrorException("cloneFrom needs credentials, even if they wrap nothing");
    }
    createMirrorDirectory();
    String[] argv =
        credentials.wrap("clone", "--mirror", "--quiet", "--end-of-options", upstreamUrl, gitDir.toString());
    GitCli.Result result =
        wire("Could not clone " + upstreamUrl + " into the mirror of " + repoId, null, argv);
    if (result.exitCode() != 0) {
      deleteQuietly(gitDir);
      throw new GitMirrorException(
          "Could not clone " + upstreamUrl + " into the mirror of " + repoId + ": " + result.output());
    }
  }

  /**
   * Fetches {@code ref} from {@code upstreamUrl} into {@code FETCH_HEAD} only — no local ref moves.
   * This is the pull's read: the caller resolves {@code FETCH_HEAD} with {@link #resolve} and
   * decides fast-forward, diverge or reconcile before anything is written, and the mirror's own
   * refs never change except through {@link #push}.
   */
  public void fetchIntoFetchHead(String upstreamUrl, String ref, GitCredentials credentials) {
    requireUrl("upstreamUrl", upstreamUrl);
    requireRefName("ref", ref);
    if (credentials == null) {
      throw new GitMirrorException("fetchIntoFetchHead needs credentials, even if they wrap nothing");
    }
    String[] argv = credentials.wrap("fetch", "--end-of-options", upstreamUrl, ref);
    GitCli.Result result = wire("Could not fetch " + ref + " from " + upstreamUrl, gitDir, argv);
    if (result.exitCode() != 0) {
      throw new GitMirrorException(
          "Could not fetch " + ref + " from " + upstreamUrl + ": " + result.output());
    }
  }

  // -----------------------------------------------------------------------------------------
  // wire reads
  // -----------------------------------------------------------------------------------------

  /**
   * The sha the git host currently holds for a branch, or empty when it has no such branch.
   *
   * <p>{@code ls-remote} rather than a mirror read on purpose. This is what "does the branch still
   * exist" is decided by, and that decision must come from the repository of record and never from
   * a cache that may be one fetch behind.
   */
  public Optional<String> remoteBranchSha(String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return Optional.empty();
    }
    GitCli.Result result =
        wire(
            "Could not read " + branch + " of " + repoId,
            null,
            "git",
            "ls-remote",
            "--heads",
            "--end-of-options",
            remotes.fetchUrl(repoId),
            "refs/heads/" + branch);
    if (result.exitCode() != 0) {
      throw new GitMirrorException(
          "Could not read '" + branch + "' of " + repoId + ": " + result.output());
    }
    return result
        .output()
        .lines()
        .map(String::trim)
        .filter(line -> line.endsWith("\trefs/heads/" + branch))
        .map(line -> line.substring(0, line.indexOf('\t')))
        .findFirst();
  }

  /** Whether the git host has this branch. */
  public boolean remoteHasBranch(String branch) {
    return remoteBranchSha(branch).isPresent();
  }

  /** Every branch the git host holds, short-named. */
  public List<String> remoteBranches() {
    GitCli.Result result =
        wire(
            "Could not list the branches of " + repoId,
            null,
            "git",
            "ls-remote",
            "--heads",
            remotes.fetchUrl(repoId));
    if (result.exitCode() != 0) {
      throw new GitMirrorException(
          "Could not list the branches of " + repoId + ": " + result.output());
    }
    List<String> branches = new ArrayList<>();
    result
        .output()
        .lines()
        .map(String::trim)
        .filter(line -> line.contains("\trefs/heads/"))
        .forEach(line -> branches.add(line.substring(line.indexOf("\trefs/heads/") + 12)));
    return branches;
  }

  // -----------------------------------------------------------------------------------------
  // local reads — the caller refreshes first
  // -----------------------------------------------------------------------------------------

  /** Resolve a revision in the mirror, or empty when it names nothing there. */
  public Optional<String> resolve(String rev) {
    GitCli.Result result = local("git", "rev-parse", "--verify", "--quiet", "--end-of-options", rev);
    return result.exitCode() == 0 && !result.output().isBlank()
        ? Optional.of(result.output().trim())
        : Optional.empty();
  }

  /** Whether {@code ancestor} is already reachable from {@code descendant}. */
  public boolean isAncestor(String ancestor, String descendant) {
    return local("git", "merge-base", "--is-ancestor", "--end-of-options", ancestor, descendant)
            .exitCode()
        == 0;
  }

  /**
   * How far {@code branch} is ahead of and behind {@code parent}, both named as they are in the
   * mirror. {@link AheadBehind#UNKNOWN} when git could not resolve one of them.
   */
  public AheadBehind aheadBehind(String parent, String branch) {
    // `--left-right --count A...B` prints "<behind>\t<ahead>": commits in A not B, then B not A.
    GitCli.Result result =
        local("git", "rev-list", "--left-right", "--count", parent + "..." + branch);
    if (result.exitCode() != 0) {
      return AheadBehind.UNKNOWN;
    }
    String[] parts = result.output().trim().split("\\s+");
    if (parts.length != 2) {
      return AheadBehind.UNKNOWN;
    }
    try {
      return new AheadBehind(Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
    } catch (NumberFormatException e) {
      return AheadBehind.UNKNOWN;
    }
  }

  /**
   * The real three-way merge, in the object store, with no working tree involved ({@code merge-tree
   * --write-tree}). It exits 1 to report conflicts, which is an answer rather than a failure.
   */
  public MergeOutcome previewMerge(String target, String source) {
    GitCli.Result result =
        local("git", "merge-tree", "--write-tree", "--name-only", "--end-of-options", target, source);
    if (result.exitCode() == 0) {
      return MergeOutcome.clean(result.output());
    }
    if (result.exitCode() == 1) {
      return MergeOutcome.conflicted(conflictedFiles(result.output()), result.output());
    }
    throw new GitMirrorException(
        "Could not preview merging '"
            + source
            + "' into '"
            + target
            + "' ["
            + result.exitCode()
            + "]: "
            + result.output());
  }

  /**
   * The conflicting paths out of a conflicted {@code merge-tree --write-tree --name-only} output:
   * the lines between the written tree OID and the blank separator before the informational
   * messages.
   */
  static List<String> conflictedFiles(String mergeTreeOutput) {
    List<String> files = new ArrayList<>();
    String[] lines = mergeTreeOutput.split("\n", -1);
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) {
        break;
      }
      files.add(lines[i].trim());
    }
    return files;
  }

  // -----------------------------------------------------------------------------------------
  // plumbing — no working tree, ever
  // -----------------------------------------------------------------------------------------

  /**
   * One blob to place in a tree built by {@link #writeTree}: its git file mode, its path within the
   * tree, and its content. The explicit mode is what lets a symlink land as {@code 120000} rather
   * than a plain file.
   */
  public record TreeEntry(String path, String mode, byte[] content) {}

  /**
   * Builds a tree from a flat list of entries with no working tree and no branch touched: {@code
   * hash-object -w} every blob, {@code update-index --cacheinfo} them into a scratch index, then
   * {@code write-tree} — which derives the subtrees from the flat index, unlike {@code mktree}.
   * Returns the tree's sha.
   *
   * <p>The scratch lives under this mirror's own {@code skeleton/<repoId>/} directory (a sibling of
   * {@code mirrors/<repoId>.git}), removed on every path through a {@code finally}.
   */
  public String writeTree(List<TreeEntry> entries) {
    Path treeDir = skeletonRoot.resolve("tree");
    Path index = skeletonRoot.resolve("index");
    try {
      Files.createDirectories(treeDir);
      List<String> hashArgs = new ArrayList<>(List.of("git", "hash-object", "-w", "--no-filters"));
      for (TreeEntry entry : entries) {
        Path file = treeDir.resolve(entry.path());
        Files.createDirectories(file.getParent());
        Files.write(file, entry.content());
        hashArgs.add(file.toAbsolutePath().toString());
      }
      GitCli.Result hashed = local(hashArgs.toArray(String[]::new));
      if (hashed.exitCode() != 0) {
        throw new GitMirrorException("Could not hash the tree's blobs: " + hashed.output());
      }
      List<String> shas =
          hashed.output().lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
      if (shas.size() != entries.size()) {
        throw new GitMirrorException(
            "Expected " + entries.size() + " blob(s), git hashed " + shas.size());
      }

      // One update-index entry per blob. The index is flat, so nested paths need no directory
      // entries — write-tree derives the subtrees.
      List<String> indexArgs = new ArrayList<>(List.of("git", "update-index", "--add"));
      for (int i = 0; i < entries.size(); i++) {
        TreeEntry entry = entries.get(i);
        indexArgs.add("--cacheinfo");
        indexArgs.add(entry.mode() + "," + shas.get(i) + "," + entry.path());
      }
      Map<String, String> indexEnv = Map.of("GIT_INDEX_FILE", index.toAbsolutePath().toString());
      GitCli.Result updated = local(indexEnv, indexArgs.toArray(String[]::new));
      if (updated.exitCode() != 0) {
        throw new GitMirrorException("Could not stage the tree's entries: " + updated.output());
      }

      GitCli.Result written = local(indexEnv, "git", "write-tree");
      if (written.exitCode() != 0) {
        throw new GitMirrorException("Could not write the tree: " + written.output());
      }
      return written.output().trim();
    } catch (IOException e) {
      throw new GitMirrorException("Could not materialize the tree's blobs under " + treeDir, e);
    } finally {
      deleteQuietly(skeletonRoot);
    }
  }

  /**
   * Commits {@code treeSha} with the given parents — empty for a root commit (the project template
   * skeleton), two for a merge commit (a diverged pull) — and returns the new commit's sha. No ref
   * moves: the caller pushes the sha directly ({@link PushSpec.Ref#branch}), exactly as it would
   * push an existing branch by name.
   */
  public String commitTree(String treeSha, List<String> parents, String message, CommitIdentity identity) {
    List<String> argv = new ArrayList<>(List.of("git"));
    argv.addAll(identity.inlineArgs());
    argv.add("commit-tree");
    argv.add(treeSha);
    for (String parent : parents) {
      argv.add("-p");
      argv.add(parent);
    }
    argv.add("-m");
    argv.add(message);
    GitCli.Result result = local(identity.env(), argv.toArray(String[]::new));
    if (result.exitCode() != 0) {
      throw new GitMirrorException("Could not commit the tree: " + result.output());
    }
    return result.output().trim();
  }

  // -----------------------------------------------------------------------------------------
  // writes — every one of them a push
  // -----------------------------------------------------------------------------------------

  /**
   * Push from the mirror. The objects are already on the host for every refspec built out of a ref
   * the mirror fetched, so these pushes carry almost no bytes; what they carry is a
   * <b>post-receive</b>, which is the point.
   */
  public PushOutcome push(PushSpec spec) {
    List<String> argv = new ArrayList<>(List.of("git", "push", "--porcelain"));
    spec.options().forEach(option -> argv.add("--push-option=" + option));
    if (spec.atomic()) {
      argv.add("--atomic");
    }
    argv.add(remotes.pushUrl(repoId));
    spec.refs().forEach(ref -> argv.add(ref.refspec()));
    GitCli.Result result =
        wire("The push to " + repoId + " failed", gitDir, argv.toArray(String[]::new));
    if (result.exitCode() == 0) {
      markStale();
      return new PushOutcome(true, result.output());
    }
    return new PushOutcome(false, result.output());
  }

  /**
   * Create a branch at another branch's tip — the operation that used to be {@code git branch} in
   * the served bare, which fired no {@code post-receive} and so produced no CI run.
   *
   * <p>Pushed by ref name rather than by sha: the mirror was just refreshed, so {@code
   * refs/heads/<from>} is the tip the host has, and naming it keeps the create honest if the two
   * ever disagree — the push is refused rather than resurrecting an old commit.
   */
  public PushOutcome createBranch(String branch, String from) {
    return push(PushSpec.of(PushSpec.Ref.branch("refs/heads/" + from, branch)));
  }

  /** Delete a branch on the git host — through the protection hook, exactly like any other push. */
  public PushOutcome deleteBranch(String branch) {
    return push(PushSpec.of(PushSpec.Ref.deleteBranch(branch)));
  }

  // -----------------------------------------------------------------------------------------
  // argument checks
  // -----------------------------------------------------------------------------------------

  private static void requireUrl(String argName, String url) {
    if (url == null || url.isBlank()) {
      throw new GitMirrorException(argName + " must not be blank");
    }
  }

  private static void requireRefName(String argName, String ref) {
    if (ref == null || ref.isBlank() || ref.startsWith("-")) {
      throw new GitMirrorException(argName + " is not a valid ref name: '" + ref + "'");
    }
  }

  // -----------------------------------------------------------------------------------------
  // plumbing
  // -----------------------------------------------------------------------------------------

  private void createMirrorDirectory() {
    try {
      Files.createDirectories(gitDir.getParent());
    } catch (IOException e) {
      throw new GitMirrorException("Could not create the mirror directory " + gitDir.getParent(), e);
    }
  }

  /** A local git call in the mirror: unbounded, because a bound would only turn slow into broken. */
  GitCli.Result local(String... argv) {
    return local(Map.of(), argv);
  }

  /** As {@link #local(String...)}, with an env overlay (identity, {@code GIT_INDEX_FILE}, …). */
  GitCli.Result local(Map<String, String> env, String... argv) {
    return unbounded(gitDir.toFile(), env, argv);
  }

  private GitCli.Result unbounded(File cwd, Map<String, String> env, String... argv) {
    try {
      return cli.run(cwd, env, null, null, argv);
    } catch (Exception e) {
      throw new GitMirrorException(
          "git " + String.join(" ", argv) + " failed" + (cwd != null ? " in " + cwd : ""), e);
    }
  }

  /** A git call that talks to a remote, and therefore carries a deadline. */
  private GitCli.Result wire(String what, Path cwd, String... argv) {
    try {
      return cli.run(cwd == null ? null : cwd.toFile(), Map.of(), null, networkTimeout, argv);
    } catch (Exception e) {
      throw new GitMirrorException(what + ": " + e.getMessage(), e);
    }
  }

  private static void deleteQuietly(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException ignored) {
      // best effort — the next run retries
    }
  }
}
