package eu.wohlben.qits.projects.control;

import java.time.Instant;

/**
 * Tells the platform that <b>source control has this release</b>: this version of this repository is
 * tagged and the tag is on the git host.
 *
 * <p>A port, for the reason every other reach out of {@code domain} is one: the announcement leaves
 * over {@code qits-eventstream}'s bus, and {@code domain} does not know the event bus exists. The
 * one implementation is {@code service/…/bus/SCMReleaseAnnouncer}, which publishes {@code
 * SCMRelease}.
 *
 * <p><b>qits-projects is the publisher now, and the event shape did not move.</b> qits-workspaces
 * announced it the instant its release push was accepted, because only the process that ran {@code
 * git push} knew atomically that the push succeeded and with which version. In the tag-only release
 * flow the equivalent moment is qits-githost answering {@code 201} to the tag, and this service is
 * the one that asked — so the publisher changed and the payload deliberately did not. Every existing
 * consumer selects on the same five fields.
 *
 * <p><b>It does not mean an artifact exists.</b> Nothing is built, published or installable at this
 * moment — that statement is qits-ci's {@code SoftwareRelease}, emitted once per artifact when a
 * repository's release pipeline goes green. Between the two sits that pipeline. A consumer reading
 * this as "the package is in the registry" is reading it wrong.
 *
 * <p><b>Absent is a supported configuration</b>, like every port here: with no implementation a
 * release still lands and simply announces nothing. Injected as an {@code Instance<T>} for that
 * reason. <b>Nothing here may throw</b>, and nothing here may be called inside a transaction the
 * caller needs — the tag is irreversible the instant the git host accepts it, so an announcement
 * conditional on anything after it would be silent about a release that really happened.
 */
public interface ReleaseAnnouncer {

  /**
   * A release was tagged.
   *
   * @param projectId the project the repository belongs to, or null where it has none
   * @param repoId the repository's storage id — the git host's key
   * @param repoName the repository's registered name, or null. <b>The field a committed CI
   *     selection can address</b>, which the id is not: a row id is minted per platform instance.
   * @param branch the branch that was released — the request's backing branch, {@code release/<id>}
   * @param version the release stamp, {@code YYYY.MMDD.HHMMSS}, which is also the tag's name
   * @param occurredAt when the tag was accepted, which is when the release happened
   */
  void onReleased(
      String projectId,
      String repoId,
      String repoName,
      String branch,
      String version,
      Instant occurredAt);
}
