package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ManifestVersionBump;
import eu.wohlben.qits.projects.control.ReleaseAnnouncer;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.control.VersionStamp;
import eu.wohlben.qits.projects.error.ManifestBumpException;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>The release.</b> A gated release request arrives here and leaves as a tag on qits-githost.
 *
 * <p>This is what replaced the qits-workspaces release door on 2026-09-03, and the shape of the
 * flow changed with the caller: that door merged a branch into {@code main}, bumped, committed,
 * pushed, tagged and promoted onto a deploy branch — one operation whose every step wrote a ref
 * somebody else's CI was watching. <b>A release is a tag now.</b> {@code main} is finalized after
 * the deployment, by a later arm of this epic; what happens here is the smallest thing that can be
 * called a release:
 *
 * <ol>
 *   <li><b>Stamp</b> a calver, {@link VersionStamp} — {@code YYYY.MMDD.HHMMSS}, UTC, once per
 *       attempt and threaded through, because a slow bump would otherwise write two versions into
 *       one commit.
 *   <li><b>Bump</b> the manifests, {@link ManifestVersionBump} — read the fold's tree and its poms
 *       and package.jsons through {@link ReleaseGitHost}, splice the version into them, and hand
 *       back the new bytes. There is no worktree on this side: the fold exists only on the git host.
 *   <li><b>Commit</b> them onto the backing branch. This is the last commit before the tag and its
 *       sha is what gets tagged. A repository that renders no version commits nothing and tags the
 *       fold itself, which is a release like any other.
 *   <li><b>Tag</b> that commit with the version. A {@code 409 tag-exists} is <b>the platform's
 *       version-uniqueness guarantee</b>, not an error: somebody released that second already, so
 *       the whole attempt starts again with a fresh stamp. Bounded at {@link #ATTEMPTS}, because a
 *       name that stays taken past three seconds is not a same-second tie.
 *   <li><b>Delete</b> the branches the release consumed — the named sources and the backing branch
 *       — best effort, because by now the tag exists and nothing after it may pretend it does not.
 *       <b>Never the default branch</b>, which is stated by the caller rather than guessed at.
 *   <li><b>Announce</b> {@code SCMRelease}, over {@link ReleaseAnnouncer}, at the moment the tag was
 *       accepted. qits-projects is that event's publisher now; its payload is unchanged.
 * </ol>
 *
 * <p>Recording the tag as pending a merge to {@code main}, and moving the request to RELEASED, are
 * deliberately <b>not</b> here: they are rows in this service's own database and belong to {@code
 * ReleaseRequests}, which calls this and settles what comes back.
 *
 * <p><b>Never throws, and every failure is classified.</b> {@code retryable} is the port's word for
 * "the moment failed" — an unreachable git host, a 5xx, a ref that moved under us, a tag name that
 * stayed taken — and the sweep retries exactly those. A manifest that will not parse, a backing
 * branch that is gone and a rev that does not resolve answer the same forever and are refusals about
 * the ask, which the sweep leaves standing until a re-arm changes it.
 *
 * <p><b>Where the retry restarts is the whole of the tag-exists arm.</b> A fresh attempt re-reads
 * the tree at the tip the previous attempt left behind and re-bumps it to the new version, rather
 * than re-tagging the same commit under another name: the manifests inside the commit have to carry
 * the version the tag says, or the released artifacts would name a version nothing built.
 */
@ApplicationScoped
@DefaultBean
public class GitHostReleaseExecutor implements ReleaseExecutor {

  private static final Logger LOG = Logger.getLogger(GitHostReleaseExecutor.class);

  /**
   * How many calvers one release will burn on {@code tag-exists}. The stamp has one-second
   * resolution, so a tie is a genuine same-second race with a sibling release and one more attempt
   * settles it; a name still taken on the third attempt is something else, and knocking is not what
   * fixes it.
   */
  static final int ATTEMPTS = 3;

  @Inject ReleaseGitHost gitHost;

  @Inject Instance<ReleaseAnnouncer> announcers;

  @Override
  public Outcome release(Release release) {
    String ref = "refs/heads/" + release.backingBranch();
    // Where the next attempt reads its tree from: the fold to begin with, then whatever the last
    // attempt's bump commit left on the branch.
    String tip = release.mergedSha();

    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      String version = VersionStamp.of(Instant.now());

      ManifestVersionBump.Result bumped;
      try {
        bumped = ManifestVersionBump.stamp(new GitHostTree(release.repoId(), tip), version);
      } catch (Unreadable e) {
        // A read that failed for a reason of the moment: the port already classified it.
        return e.retryable()
            ? Outcome.refusedRetryable("the manifests could not be read: " + e.getMessage())
            : Outcome.refused("the manifests could not be read: " + e.getMessage());
      } catch (ManifestBumpException e) {
        // A manifest that will not parse, or declares no version. Final until something changes it.
        return Outcome.refused("the manifests could not be stamped: " + e.getMessage());
      }

      String tagged;
      if (bumped.files().isEmpty()) {
        // Nothing renders a version. The fold itself is what the tag names — a stackless repository
        // releases exactly like every other one, it just has no commit before its tag.
        tagged = tip;
      } else {
        ReleaseGitHost.Answer<String> commit =
            gitHost.commit(
                release.repoId(),
                ref,
                "release(" + version + "): " + summaryOf(release),
                bumped.files());
        if (!commit.ok()) {
          return refusal("the version bump could not be committed: " + commit.detail(), commit.retryable());
        }
        tagged = commit.value();
      }

      ReleaseGitHost.TagAnswer tag =
          gitHost.tag(
              release.repoId(), version, tagged, "release(" + version + "): " + summaryOf(release));
      switch (tag.result()) {
        case CREATED -> {
          Instant releasedAt = Instant.now();
          LOG.infof(
              "Release request %s tagged %s at %s (%d manifest(s) bumped)",
              release.requestId(), version, tagged, bumped.files().size());
          // Everything past here is after the fact: the tag exists and the release happened, so
          // neither a failed branch delete nor a failed announcement may turn it into a failure.
          deleteConsumedBranches(release);
          announce(release, version, releasedAt);
          return Outcome.released(version, tagged);
        }
        case ALREADY_EXISTS -> {
          // Somebody released this second. Stamp again — and re-bump, because the manifests inside
          // the commit have to carry the version the tag says.
          LOG.infof(
              "Release request %s: %s is already tagged; re-stamping (attempt %d of %d)",
              release.requestId(), version, attempt, ATTEMPTS);
          tip = tagged;
        }
        case FAILED -> {
          return refusal("the release could not be tagged: " + tag.detail(), tag.retryable());
        }
      }
    }
    // Every attempt collided. That is not a fact about this request, so the sweep tries again.
    return Outcome.refusedRetryable(
        "every one of " + ATTEMPTS + " stamped versions was already tagged; something else is"
            + " releasing this repository");
  }

  private static Outcome refusal(String detail, boolean retryable) {
    return retryable ? Outcome.refusedRetryable(detail) : Outcome.refused(detail);
  }

  private static String summaryOf(Release release) {
    return release.summary() == null || release.summary().isBlank()
        ? "release request " + release.requestId()
        : release.summary();
  }

  /**
   * The branches this release consumed: its named sources and its own backing branch.
   *
   * <p><b>The default branch is never among them</b>, and the exclusion is made from the value the
   * caller read off the repository row rather than from the string {@code "main"} — a repository
   * whose default branch is called something else must not have it deleted by a spelling mistake.
   * qits-githost refuses its own default branch too, which makes this the near half of a seatbelt
   * rather than the only one.
   */
  private void deleteConsumedBranches(Release release) {
    Set<String> branches = new LinkedHashSet<>(release.namedSources());
    branches.remove(release.defaultBranch());
    branches.add(release.backingBranch());
    for (String branch : branches) {
      gitHost.deleteBranch(release.repoId(), branch);
    }
  }

  /** Fire and forget, outside everything, and never able to fail a release that already happened. */
  private void announce(Release release, String version, Instant releasedAt) {
    if (!announcers.isResolvable()) {
      return;
    }
    try {
      announcers
          .get()
          .onReleased(
              release.projectId(),
              release.repoId(),
              release.repoName(),
              release.backingBranch(),
              version,
              releasedAt);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not announce the release of %s as %s", release.repoId(), version);
    }
  }

  /**
   * A git-host read that failed, carried out of {@link ManifestVersionBump.Source#read} — which may
   * only throw {@link ManifestBumpException} — with the port's classification intact. Extending that
   * type is what lets the bump engine stay ignorant of HTTP while the executor still tells "the host
   * was unreachable" from "this pom is malformed", which are opposite answers to "retry?".
   */
  private static final class Unreadable extends ManifestBumpException {
    private final boolean retryable;

    Unreadable(String message, boolean retryable) {
      super(message);
      this.retryable = retryable;
    }

    boolean retryable() {
      return retryable;
    }
  }

  /** One commit's tree at the git host, as the bump engine reads it. */
  private final class GitHostTree implements ManifestVersionBump.Source {

    private final String repoId;
    private final String rev;
    private List<String> paths;

    private GitHostTree(String repoId, String rev) {
      this.repoId = repoId;
      this.rev = rev;
    }

    @Override
    public List<String> paths() {
      if (paths == null) {
        ReleaseGitHost.Answer<List<String>> answer = gitHost.tree(repoId, rev);
        if (!answer.ok()) {
          throw new Unreadable(answer.detail(), answer.retryable());
        }
        paths = answer.value();
      }
      return paths;
    }

    @Override
    public String read(String path) {
      ReleaseGitHost.Answer<String> answer = gitHost.file(repoId, rev, path);
      if (!answer.ok()) {
        throw new Unreadable(answer.detail(), answer.retryable());
      }
      return answer.value();
    }
  }
}
