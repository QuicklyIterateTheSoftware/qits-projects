package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * <b>The publish phase: a released tag reaches {@code main} once what it released is live.</b> The
 * far end of the flow {@code ReleaseRequests} opens — a release is a tag, {@code main} is finalized
 * afterwards — and the only thing on this platform that advances {@code main} at all.
 *
 * <h2>The gate</h2>
 *
 * <p>The terminal gate is <b>a deployment reporting active</b>: qits-deployments announces {@code
 * DeploymentActive} for an application and a version, and that version is a released tag of some
 * repository, waiting in {@code released_tag_pending_merge} with a null {@code merged_at}. Nothing
 * else may move {@code main}: merging before the deployment would put a commit there that nothing
 * had proved, which is the shape this epic removed.
 *
 * <p>Passing the gate stamps {@link ReleasedTagPendingMerge#mergeRequestedAt}, and that stamp — not
 * the event, not this thread — is what the merge is owed on. A merge that could not be applied stays
 * owed and the sweep keeps asking, so a git host that was unreachable for an hour costs a delay
 * rather than a release that never lands on {@code main}.
 *
 * <h2>Correlating a deployment back to a release</h2>
 *
 * <p><b>The version is the key, and it is the only key there is.</b> {@code DeploymentActive} names
 * an <em>application</em> and never a repository, and the two are not the same string — the platform
 * builds the application {@code qits-ci} out of the repository {@code qits-ci-service} — so a lookup
 * by repository identity has nothing to look up. A lookup by tag name alone is sound instead,
 * because the calver is unique platform-wide by construction: it is stamped to the second and a
 * collision comes back from the git host as {@code tag-exists}, which restarts the release with a
 * fresh stamp rather than reusing the name. Where two repositories nonetheless hold the same pending
 * tag, the application name is the tie-break and an unbreakable tie merges <b>nothing</b>: a wrong
 * repository's {@code main} is not a thing a later event can take back.
 *
 * <h2>Idempotence</h2>
 *
 * <p>Three layers, and each is load-bearing on its own: a stamped {@code merged_at} short-circuits
 * before any call is made; a repeated gate stamps {@code merge_requested_at} once; and the merge
 * itself is content-idempotent — a tag already contained in {@code main} answers {@code unchanged},
 * which moves no ref and creates no commit. So a replayed {@code DeploymentActive}, a re-deployment
 * of the same version and a sweep racing an event are all free.
 *
 * <h2>Failure</h2>
 *
 * <p>Every failed attempt writes its words onto the row and leaves it owed. A {@code 409
 * merge-conflict} is the loud one and is logged {@code ERROR} on every attempt: {@code main} only
 * ever advances through this class, and every release request folds the repository's pending tags
 * in, so a released tag that will not merge into {@code main} is an anomaly and not traffic. It is
 * still retried, because the thing that resolves it — a push to {@code main}, a sibling release
 * landing — is a change this class cannot see and must not have to be told about.
 */
@ApplicationScoped
public class ReleaseFinalization {

  private static final Logger LOG = Logger.getLogger(ReleaseFinalization.class);

  /** The fallback default branch, for a repository row that names none — {@code ReleaseRequests}'. */
  private static final String DEFAULT_MAIN = "main";

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  @Inject ReleaseRequests releaseRequests;

  @Inject Instance<BackingBranchMerger> mergers;

  /**
   * A deployment of {@code version} is live, so the tag it deployed is owed {@code main}.
   *
   * <p><b>Any environment counts, and the first one wins.</b> A version reaching {@code dev} is the
   * same commit that will reach every other tier — the deployment pulls one immutable coordinate —
   * so waiting for a particular environment would leave {@code main} behind whatever is actually
   * shipping, and every later deployment of the same version is a no-op through {@code merged_at}.
   *
   * @param applicationName the deployed application, used only to break a tie between two
   *     repositories holding the same pending tag; null is tolerated
   * @param version the released coordinate, which is the tag's own name
   * @param environmentName where it went live — for the log line and the merge message
   */
  public void onDeploymentActive(String applicationName, String version, String environmentName) {
    Owed owed = correlate(applicationName, version);
    if (owed == null) {
      return;
    }
    gate(
        owed,
        "the deployment of "
            + (applicationName == null ? "it" : applicationName)
            + " "
            + version
            + (environmentName == null ? "" : " to " + environmentName)
            + " is active");
    merge(owed.id());
  }

  /**
   * The belt under every gate: re-attempts each merge this service owes {@code main}.
   *
   * <p>It is what turns an unreachable git host, a merge that raced a concurrent writer and a
   * conflict somebody has since resolved into delays instead of stalls. <b>It selects on {@code
   * merge_requested_at} and nothing else</b>: a released tag whose deployment has not happened is
   * not owed anything, and sweeping it would be this class merging on no gate at all.
   */
  public void sweep() {
    List<String> owed =
        QuarkusTransaction.requiringNew()
            .call(() -> pendingTags.listOwedMerges().stream().map(row -> row.id).toList());
    owed.forEach(this::merge);
  }

  // -----------------------------------------------------------------------------------------------
  // The merge
  // -----------------------------------------------------------------------------------------------

  /** One row's identity, carried out of the transaction that read it. */
  record Owed(String id, String repoId, String tagName) {}

  /**
   * Stamp the gate, once. A second gate for the same tag — a re-deployment, a replayed frame, the
   * publish path and the deployment path racing — finds the stamp and changes nothing.
   */
  private void gate(Owed owed, String why) {
    boolean stamped =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .findByIdOptional(owed.id())
                        .filter(row -> row.mergedAt == null && row.mergeRequestedAt == null)
                        .map(
                            row -> {
                              row.mergeRequestedAt = Instant.now();
                              return true;
                            })
                        .orElse(false));
    if (stamped) {
      LOG.infof(
          "The released tag %s of %s is owed main: %s", owed.tagName(), owed.repoId(), why);
    }
  }

  /**
   * Merge one owed tag into its repository's default branch, and act on what came back.
   *
   * <p>The git-host call is made <b>outside</b> every transaction, the shape {@code
   * ReleaseRequests.remerge} already takes: a short read, a round trip, a short write.
   *
   * <p><b>The source is the released sha, never the tag ref.</b> The branches a release consumed are
   * deleted when it lands, and the tag is a ref somebody could move or delete; the sha recorded when
   * the release happened is the fact, and it is what {@code released_tag_pending_merge} exists to
   * remember.
   */
  private void merge(String rowId) {
    record Ask(String repoId, String tagName, String sha, String target, boolean owed) {}
    Ask ask =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .findByIdOptional(rowId)
                        .filter(row -> row.mergedAt == null && row.mergeRequestedAt != null)
                        .map(
                            row ->
                                new Ask(
                                    row.repoId,
                                    row.tagName,
                                    row.releasedSha,
                                    "refs/heads/" + mainOf(row.repoId),
                                    true))
                        .orElse(new Ask(null, null, null, null, false)));
    if (!ask.owed()) {
      return;
    }
    String what = ask.tagName() + " of " + ask.repoId();
    if (!mergers.isResolvable()) {
      failed(
          rowId,
          what,
          "No git host is configured; the released tag "
              + ask.tagName()
              + " cannot be merged into "
              + ask.target(),
          false);
      return;
    }
    BackingBranchMerger.Outcome outcome;
    try {
      outcome =
          mergers
              .get()
              .merge(
                  ask.repoId(),
                  ask.target(),
                  List.of(ask.sha()),
                  "Release " + ask.tagName() + " is deployed; finalizing " + ask.target());
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not lose the merge.
      LOG.warnf(e, "The merger threw finalizing %s of %s", ask.tagName(), ask.repoId());
      outcome = BackingBranchMerger.Outcome.unreachable("merger error: " + e.getMessage());
    }
    if (!outcome.folded()) {
      failed(
          rowId,
          what,
          detailOf(outcome, ask.target()),
          outcome.result() == BackingBranchMerger.Result.CONFLICT);
      return;
    }
    landed(rowId);
    // The row's own merged_at is stamped THERE and only there — one writer of that column — and the
    // repository's open requests re-fold without this tag, which is content-idempotent and usually
    // answers `unchanged`.
    releaseRequests.onReleasedTagMerged(ask.repoId(), ask.tagName());
    LOG.infof(
        "The released tag %s of %s reached %s (%s)",
        ask.tagName(), ask.repoId(), ask.target(), outcome.result());
  }

  /** The attempt landed: the row keeps no stale reason for a failure it recovered from. */
  private void landed(String rowId) {
    QuarkusTransaction.requiringNew()
        .run(() -> pendingTags.findByIdOptional(rowId).ifPresent(row -> row.mergeDetail = null));
  }

  /**
   * The attempt did not land: the reason goes on the row, the row stays owed, and the sweep asks
   * again.
   *
   * <p>Loud on a conflict every time, and loud once on anything else — a git host that is down for
   * ten minutes is twenty sweeps, and twenty ERRORs about one outage is a log nobody reads.
   */
  private void failed(String rowId, String what, String detail, boolean conflict) {
    boolean first =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .findByIdOptional(rowId)
                        .map(
                            row -> {
                              boolean changed = !detail.equals(row.mergeDetail);
                              row.mergeDetail = detail;
                              return changed;
                            })
                        .orElse(false));
    if (conflict) {
      LOG.errorf(
          "A released tag CANNOT be merged into main and that is an anomaly, not traffic (%s): %s",
          what, detail);
    } else if (first) {
      LOG.errorf("A released tag is owed main and the merge did not apply (%s): %s", what, detail);
    } else {
      LOG.warnf("Still owed main (%s): %s", what, detail);
    }
  }

  private static String detailOf(BackingBranchMerger.Outcome outcome, String target) {
    if (outcome.result() == BackingBranchMerger.Result.CONFLICT) {
      return "The release conflicts with "
          + target
          + ": "
          + outcome.conflicts().stream()
              .map(BackingBranchMerger.Conflict::path)
              .collect(Collectors.joining(", "));
    }
    return outcome.detail() == null ? "The git host could not be asked" : outcome.detail();
  }

  // -----------------------------------------------------------------------------------------------
  // Correlation
  // -----------------------------------------------------------------------------------------------

  /**
   * Which pending released tag a deployed {@code (application, version)} is, or null when none is.
   *
   * <p>Three answers and all three are ordinary. <b>No row</b> is a version this service did not
   * release — every deployment on the platform passes through here — or one whose merge already
   * landed, and both are a DEBUG and a return. <b>One row</b> is the answer. <b>Several rows</b> is
   * the same calver held by two repositories, which the platform's stamp makes vanishingly unlikely
   * and does not make impossible; the application name breaks the tie, and where it cannot, nothing
   * is merged and the rows stay visibly unfinished. A wrong {@code main} is not recoverable and a
   * late one is.
   */
  private Owed correlate(String applicationName, String version) {
    if (version == null || version.isBlank()) {
      return null;
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<ReleasedTagPendingMerge> rows = pendingTags.listByTag(version.trim());
              List<ReleasedTagPendingMerge> open =
                  rows.stream().filter(row -> row.mergedAt == null).toList();
              if (open.isEmpty()) {
                LOG.debugf(
                    "Nothing here owes main for version %s (%d released rows carry that tag)",
                    version, rows.size());
                return null;
              }
              if (open.size() == 1) {
                return owedOf(open.get(0));
              }
              List<ReleasedTagPendingMerge> named =
                  open.stream().filter(row -> answersTo(row.repoId, applicationName)).toList();
              if (named.size() == 1) {
                LOG.infof(
                    "Version %s is pending in %d repositories; the application name %s names one of"
                        + " them",
                    version, open.size(), applicationName);
                return owedOf(named.get(0));
              }
              LOG.errorf(
                  "Version %s is pending a merge to main in %d repositories and the application"
                      + " name %s tells them apart in %d of them; NOTHING is merged, because a"
                      + " wrong main cannot be taken back. Merge one by hand and the rest follow.",
                  version, open.size(), applicationName, named.size());
              return null;
            });
  }

  private static Owed owedOf(ReleasedTagPendingMerge row) {
    return new Owed(row.id, row.repoId, row.tagName);
  }

  /**
   * Whether a repository is plausibly what an application was built from. The platform's own
   * grammar and nothing cleverer: the application {@code qits-ci} is built from {@code
   * qits-ci-service}, a repository with no role suffix answers to its own name, and this is only
   * ever asked to break a tie between rows that already share a version.
   */
  private boolean answersTo(String repoId, String applicationName) {
    if (applicationName == null || applicationName.isBlank()) {
      return false;
    }
    String application = applicationName.trim();
    return repositories
        .findByIdOptional(repoId)
        .flatMap(names::nameFor)
        .map(name -> name.equals(application) || name.startsWith(application + "-"))
        .orElse(false);
  }

  /** The repository's default branch — {@code ReleaseRequests}' reading, which is private there. */
  private String mainOf(String repoId) {
    return repositories
        .findByIdOptional(repoId)
        .map(repository -> repository.mainBranch)
        .filter(branch -> branch != null && !branch.isBlank())
        .orElse(DEFAULT_MAIN);
  }
}
