package eu.wohlben.qits.projects.control;

import java.time.Instant;

/**
 * Tells the rest of the platform that something happened to a repository's public identity.
 *
 * <p>A port, for the reason every other reach out of {@code domain} is one: the announcement leaves
 * over {@code qits-eventstream}'s bus, and {@code domain} does not know the event bus exists. The
 * one implementation is {@code service/…/bus/RepositoryRenamedAnnouncer}, and it is what makes this
 * service a <b>publisher</b> for the first time — until the rename endpoint landed, {@code
 * service/…/bus/} was consume-only.
 *
 * <p><b>Absent is a supported configuration</b>, like every port here: with no implementation a
 * rename still renames and simply announces nothing, which is what {@code domain}'s own suite runs
 * as. Injected as an {@code Instance<T>} for that reason.
 *
 * <p><b>Nothing here may throw</b> and nothing here may be called inside a transaction the caller
 * needs. The bus's own {@code publish} is fire-and-forget and never throws — see {@code
 * QitsEventBus} — but the port states the rule anyway, because an announcement that could fail a
 * rename would make the platform's bookkeeping a way for the operation to fail.
 *
 * @see #onRepositoryRenamed
 */
public interface RepositoryAnnouncer {

  /**
   * A repository answers to a new name now. The bare on the git host did not move — it is keyed by
   * the row's opaque id — so what changed is the public coordinate {@code /git/<project>/<name>}
   * and nothing else.
   *
   * @param projectId the project the name is scoped to; a name is unique per project, never
   *     globally
   * @param repositoryId the row's id, which is also the git host's storage key and is unchanged
   * @param oldName what the repository answered to before, or null for a row that answered to
   *     nothing
   * @param newName the only name it answers to now
   * @param renamedAt when the rename committed — the event's {@code occurredAt}, not the moment the
   *     announcement is made
   */
  void onRepositoryRenamed(
      String projectId, String repositoryId, String oldName, String newName, Instant renamedAt);
}
