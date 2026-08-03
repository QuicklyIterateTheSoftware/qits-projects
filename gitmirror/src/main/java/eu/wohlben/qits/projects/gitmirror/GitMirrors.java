package eu.wohlben.qits.projects.gitmirror;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry: one {@link RepoMirror} per repository id, all under one root directory.
 *
 * <p>This is qits-workspaces' {@code gitmirror} pattern, unchanged: a private mirror per repository
 * on this service's own volume, filled by cloning or fetching over the wire. Neither this class nor
 * anything it builds ever touches the shared {@code qits-repositories} volume — that coupling is
 * exactly what this module exists to remove.
 *
 * <p><b>What the lock covers, and why it is only that.</b> Cloning and fetching one repository are
 * serialized per repository id, because two of them at once would race for the same object store for
 * no gain. Reads and pushes are not: they are what a listing and a sync do, and they are safe
 * concurrently.
 */
public final class GitMirrors {

  private final GitCli cli;
  private final GitRemotes remotes;
  private final Path root;
  private final Duration networkTimeout;
  private final Duration freshness;

  private final Map<String, RepoMirror> mirrors = new ConcurrentHashMap<>();

  /**
   * @param root where the mirrors live — this service's OWN data volume, never the shared
   *     repositories tree
   * @param networkTimeout the bound on every wire call: clone, fetch, {@code ls-remote}, push
   * @param freshness how long a fetched mirror is trusted before {@link RepoMirror#refresh()}
   *     fetches again. Zero means every refresh fetches; the flows that cannot tolerate a stale
   *     answer call {@link RepoMirror#refreshNow()} regardless of it.
   */
  public GitMirrors(
      GitCli cli, GitRemotes remotes, Path root, Duration networkTimeout, Duration freshness) {
    this.cli = cli;
    this.remotes = remotes;
    this.root = root.toAbsolutePath();
    this.networkTimeout = networkTimeout;
    this.freshness = freshness;
  }

  /**
   * The mirror for a repository. Cheap and lazy — nothing is cloned until a caller asks for
   * something that needs objects.
   */
  public RepoMirror of(String repoId) {
    if (repoId == null || repoId.isBlank()) {
      throw new GitMirrorException("A mirror needs a repository id");
    }
    return mirrors.computeIfAbsent(
        repoId, id -> new RepoMirror(cli, remotes, id, root, networkTimeout, freshness));
  }

  /** Where the mirrors live. */
  public Path root() {
    return root;
  }
}
