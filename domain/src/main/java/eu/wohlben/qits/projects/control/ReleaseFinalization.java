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
 * <h2>The repositories that have no deployment to wait for</h2>
 *
 * <p>A library deploys nothing, and neither does a stand-alone SPA or a docs repository, so the gate
 * above would never come and their {@code main} would never move again. {@link #onReleased} is the
 * shortcut: <b>at the release</b>, the released tag's tree is read and a repository declaring no
 * {@code .config/qits/deployments.yml} is finalized there and then. <b>That fork lives in exactly
 * one place</b> — {@link #deployability}, reached only from {@link #fork} — and it is
 * <b>temporary</b>, in the sense that its replacement is already named: when qits-maintenance
 * becomes the lifecycle for libraries the way qits-deployments is for services, a consumer taking
 * the new version <em>is</em> the deployment, and this arm goes.
 *
 * <p><b>It hangs off this service's own release and not off qits-ci's {@code SoftwareRelease}</b>,
 * which is where it used to hang and why the arm quietly did nothing for half the platform: that
 * event is emitted by a repository's {@code ci-event-release.yml} recipe, so a repository without
 * one published no artifact event, was never forked on, and left its released tag stranded off
 * {@code main} for ever. A release, by contrast, is something this service performs itself and
 * therefore always knows about.
 *
 * <p>The two gates cannot both fire for one tag: a repository that declares a deployment is left
 * entirely to {@code DeploymentActive}, and the stamped {@code merge_requested_at} settles the race
 * even if it could.
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

  @Inject Instance<ReleaseGitHost> gitHosts;

  /**
   * The platform's declaration that a repository is deployed, at the path every service and every
   * frontend of it carries. Its <b>absence</b> is what the non-deployable shortcut turns on.
   */
  static final String DEPLOYMENTS_MANIFEST = ".config/qits/deployments.yml";

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
   * <b>A release landed, so decide which kind of repository it belongs to.</b> Called on the release
   * worker the instant {@code ReleaseRequests} has recorded the tag, and again by the catch-up for
   * any released tag nothing has decided about yet.
   *
   * <p><b>It hangs off the RELEASE, not off a published artifact</b> — and that move is this
   * method's whole point (2026-09-04). It used to be {@code onSoftwareRelease}, driven by qits-ci's
   * per-artifact publication event, which sounded like the closest thing a library has to "it is
   * live" and was in fact a gate only some repositories can pass: {@code SoftwareRelease} is emitted
   * by a repository's {@code ci-event-release.yml} recipe, and every repository without one — every
   * SPA, for a start — announced nothing, so its released tag never reached {@code main} and its
   * default branch fell one commit behind for ever (qits-deployments-platform-frontend
   * 2026.904.151913, measured). A release is a fact this service produces itself, so this is now
   * hung off that.
   *
   * <p><b>TEMPORARY, and the shape of what replaces it is known.</b> A library deploys nothing, so
   * the {@code DeploymentActive} gate would never come and its {@code main} would never move again;
   * until qits-maintenance becomes the lifecycle for libraries the way qits-deployments is for
   * services — a consumer taking the new version <em>is</em> the deployment — the release itself has
   * to stand in for it. When that lands, this arm goes and {@code onDeploymentActive} is the only
   * gate again.
   *
   * <p>Deployability is read at the released tag, not at {@code main}: {@code
   * .config/qits/deployments.yml} is the platform's own declaration of "this is deployed", and the
   * tag is the only tree that is certainly the release's own. <b>A repository that does deploy is
   * left alone</b> — its {@code DeploymentActive} is the gate and merging here would put the commit
   * on {@code main} before the deployment, which is precisely the ordering this epic exists to fix.
   *
   * <p><b>It never throws.</b> The caller is a release that has already happened and must not be
   * failed by anything after the tag; a git host that could not answer leaves the row ungated, which
   * is precisely what {@link #sweep()}'s catch-up picks up.
   */
  public void onReleased(String repoId, String version) {
    if (repoId == null || repoId.isBlank() || version == null || version.isBlank()) {
      return;
    }
    Owed owed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .find(repoId, version.trim())
                        .filter(row -> row.mergedAt == null && row.mergeRequestedAt == null)
                        .map(ReleaseFinalization::owedOf)
                        .orElse(null));
    if (owed == null) {
      // Either this service did not release that tag, or it is already merged, or something has
      // already gated it — the deployment, or an earlier pass of this very method.
      LOG.debugf("Nothing to gate for %s of %s on its release", version, repoId);
      return;
    }
    fork(owed, true);
  }

  /**
   * The fork itself, and the ONLY copy of it: deploys nothing → {@code main} now; deploys → the
   * deployment gates it; could not tell → nothing, and the catch-up asks again.
   *
   * @param loud whether an unanswerable git host is worth a WARN. It is on the release path, where
   *     it is news; it is not on the catch-up, which re-asks about the same row every sweep and
   *     would otherwise turn one unreadable tag into a log nobody reads.
   */
  private void fork(Owed owed, boolean loud) {
    switch (deployability(owed.repoId(), owed.tagName())) {
      case DEPLOYS ->
          // The DeploymentActive path owns this one, exclusively. Saying so at DEBUG rather than
          // silently, because "nothing happened" is the correct outcome and an unreadable one.
          LOG.debugf(
              "%s declares a deployment, so its release %s reaches main when that deployment does",
              owed.repoId(), owed.tagName());
      case DEPLOYS_NOTHING -> {
        gate(
            owed,
            "the repository declares no "
                + DEPLOYMENTS_MANIFEST
                + ", so nothing will ever deploy "
                + owed.tagName());
        merge(owed.id());
      }
      case UNREADABLE, UNKNOWN_FOR_NOW -> {
        // The released tag stays visibly unfinished — merge_requested_at null beside a null
        // merged_at — and the catch-up, a deployment, or a person can still complete it.
        String sentence =
            "Whether "
                + owed.tagName()
                + " of "
                + owed.repoId()
                + " deploys anything cannot be established; it stays ungated and the catch-up will"
                + " ask again";
        if (loud) {
          LOG.warn(sentence);
        } else {
          LOG.debug(sentence);
        }
      }
    }
  }

  /**
   * The belt under every gate, in two arms.
   *
   * <p><b>The catch-up</b> re-asks the deployability question for every released tag nothing has
   * gated yet. It is what makes the fork crash-safe — a process that died between the tag and the
   * fork loses nothing but time — and it is what heals a tag whose fork never ran at all, which is
   * every non-deployable release made while this decision hung off {@code SoftwareRelease}. A
   * repository that does declare a deployment is looked at and left alone on every pass, which is
   * one cheap tree listing per release in flight.
   *
   * <p><b>The retry</b> re-attempts each merge this service already owes {@code main}, turning an
   * unreachable git host, a merge that raced a concurrent writer and a conflict somebody has since
   * resolved into delays instead of stalls. <b>It selects on {@code merge_requested_at} and nothing
   * else</b>: a released tag whose deployment has not happened is not owed anything, and sweeping it
   * would be this class merging on no gate at all.
   */
  public void sweep() {
    List<String> owed =
        QuarkusTransaction.requiringNew()
            .call(() -> pendingTags.listOwedMerges().stream().map(row -> row.id).toList());
    owed.forEach(this::merge);
    List<Owed> ungated =
        QuarkusTransaction.requiringNew()
            .call(() -> pendingTags.listUngated().stream().map(ReleaseFinalization::owedOf).toList());
    ungated.forEach(row -> fork(row, false));
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
  // Deployable or not — the TEMPORARY fork, and the only copy of it
  // -----------------------------------------------------------------------------------------------

  /**
   * What a repository's released tree says about whether anything deploys it. {@link #UNKNOWN} is
   * not a third kind of repository: it is this service not having been able to ask.
   */
  enum Deployability {
    DEPLOYS,
    DEPLOYS_NOTHING,
    /** The git host could not be asked. Retrying is exactly what fixes it. */
    UNKNOWN_FOR_NOW,
    /** The git host answered, and its answer was a refusal that will not change: settle. */
    UNREADABLE
  }

  /**
   * Does this release declare a deployment? Read as the released tag's own tree, through the git
   * host, and nowhere else.
   *
   * <p><b>The tree rather than the file.</b> {@code ReleaseGitHost.file} answers "failed" for a blob
   * that is absent, one that is binary and a rev that does not resolve alike, and the difference
   * between "this repository deploys nothing" and "the git host could not tell us" is the whole
   * decision here. A tree listing separates them: a successful listing without the path is an
   * answer, and an unsuccessful one is not an answer at all.
   *
   * <p>A refusal that is <b>not</b> about the moment — a tag the git host does not know — is kept
   * apart as {@link Deployability#UNREADABLE} even though {@link #fork} treats the two alike today:
   * the same bytes really would fail identically forever, and only the volume of the retry
   * distinguishes them. Neither is ever read as an answer.
   *
   * <p><b>Its failures are logged at DEBUG</b>, because the catch-up asks about the same row on
   * every sweep; {@link #fork} is what says something once, on the path where it is news.
   */
  private Deployability deployability(String repoId, String version) {
    if (!gitHosts.isResolvable()) {
      LOG.debugf(
          "No git host is configured, so whether %s deploys anything cannot be read", repoId);
      return Deployability.UNKNOWN_FOR_NOW;
    }
    ReleaseGitHost.Answer<List<String>> tree;
    try {
      tree = gitHosts.get().tree(repoId, "refs/tags/" + version);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not be read as an answer.
      LOG.debugf(e, "The git host threw reading the tree of %s at %s", repoId, version);
      return Deployability.UNKNOWN_FOR_NOW;
    }
    if (!tree.ok()) {
      LOG.debugf(
          "Could not read the tree of %s at refs/tags/%s (%s): %s",
          repoId, version, tree.retryable() ? "retryable" : "final", tree.detail());
      return tree.retryable() ? Deployability.UNKNOWN_FOR_NOW : Deployability.UNREADABLE;
    }
    return tree.value().contains(DEPLOYMENTS_MANIFEST)
        ? Deployability.DEPLOYS
        : Deployability.DEPLOYS_NOTHING;
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
