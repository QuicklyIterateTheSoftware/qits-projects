package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ManifestVersionBump;
import eu.wohlben.qits.projects.control.ReleaseAnnouncer;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.control.VersionStamp;
import eu.wohlben.qits.projects.control.WrapperGitmodules;
import eu.wohlben.qits.projects.error.ManifestBumpException;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *       fold itself, which is a release like any other — except the project's WRAPPER, whose
 *       release also <b>banks the estate</b>: every declared submodule pinned as a gitlink at its
 *       default branch's head, in this same commit ({@link #bankGitlinks}).
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

      Banked banked = bankGitlinks(release, tip);
      if (banked.refusal() != null) {
        return banked.refusal();
      }

      String tagged;
      if (bumped.files().isEmpty() && banked.gitlinks().isEmpty()) {
        // Nothing renders a version. The fold itself is what the tag names — a stackless repository
        // releases exactly like every other one, it just has no commit before its tag.
        tagged = tip;
      } else {
        ReleaseGitHost.Answer<String> commit =
            gitHost.commit(
                release.repoId(),
                ref,
                "release(" + version + "): " + summaryOf(release),
                bumped.files(),
                banked.gitlinks());
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
          announce(release, version, tagged, releasedAt);
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

  /** What the banking arm decided: the pins to write, or the refusal that stops the release. */
  private record Banked(Map<String, String> gitlinks, Outcome refusal) {}

  /**
   * A wrapper release banks its estate: every submodule {@code .gitmodules} declares, pinned at the
   * head of its repository's default branch, as gitlink entries in the same commit as the version
   * bump. The release itself is what moves the pins — nothing updates them between releases, which
   * is the point: a fresh clone's {@code submodule update --init} lands on the estate as it was
   * released, and the drift in between never touches a ref.
   *
   * <p>Empty for every ordinary repository — {@code wrapperCatalog} is only populated for the
   * project's WRAPPER, so this arm costs nothing anywhere else. The paths are {@code .gitmodules}'s
   * (read at the fold, the path authority), the names are matched against the catalog, and each
   * head is asked of the git host at this moment: pins are facts about refs, and refs live there.
   *
   * <p>A declared submodule the catalog cannot name is skipped with a WARN and its existing pin
   * stays as it is — a half-reconciled estate must not fail a release — but a head that cannot be
   * <em>read</em> refuses it: writing a partial bank would silently pin part of the estate stale,
   * which is worse than either outcome the caller can see.
   */
  private Banked bankGitlinks(Release release, String tip) {
    if (release.wrapperCatalog() == null || release.wrapperCatalog().isEmpty()) {
      return new Banked(Map.of(), null);
    }
    ReleaseGitHost.Answer<String> gitmodules = gitHost.file(release.repoId(), tip, ".gitmodules");
    if (!gitmodules.ok()) {
      return new Banked(
          null,
          refusal(
              "the wrapper's .gitmodules could not be read, so its estate cannot be banked: "
                  + gitmodules.detail(),
              gitmodules.retryable()));
    }
    Map<String, String> gitlinks = new LinkedHashMap<>();
    for (WrapperGitmodules.Entry entry : WrapperGitmodules.entries(gitmodules.value())) {
      ReleaseExecutor.Submodule submodule = release.wrapperCatalog().get(entry.name());
      if (submodule == null) {
        LOG.warnf(
            "The wrapper declares submodule %s at %s, which the catalog does not name; its pin"
                + " stays as it is",
            entry.name(), entry.path());
        continue;
      }
      ReleaseGitHost.Answer<String> head =
          gitHost.head(submodule.repoId(), submodule.mainBranch());
      if (!head.ok()) {
        return new Banked(
            null,
            refusal(
                "the head of "
                    + entry.name()
                    + "'s "
                    + submodule.mainBranch()
                    + " could not be read, so the estate cannot be banked: "
                    + head.detail(),
                head.retryable()));
      }
      gitlinks.put(entry.path(), head.value());
    }
    return new Banked(Map.copyOf(gitlinks), null);
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

  /**
   * Fire and forget, outside everything, and never able to fail a release that already happened.
   *
   * <p>{@code tagged} is the commit the tag was created at — the bump commit, or the fold where
   * nothing renders a version — and it rides out as the event's {@code commitSha}. It is the same
   * value {@link ReleaseExecutor.Outcome#released} returns and the same one {@code
   * ReleasedTagPendingMerge.releasedSha} records, deliberately: three statements about one release
   * that a reader can join, where before this the bus half named a tag and no commit and a release
   * pipeline had to go and find the commit itself.
   */
  private void announce(Release release, String version, String tagged, Instant releasedAt) {
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
              tagged,
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
