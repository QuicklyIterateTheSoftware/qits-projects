package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseRequestAnnouncer;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's {@link ReleaseRequestAnnouncer}: records what would have been published, so a test can
 * assert both that an event was dispatched and — the harder half — that one was <b>not</b>. An
 * ordinary bean over the {@code @DefaultBean} announcer, so no test reaches the event bus.
 */
@ApplicationScoped
public class RecordingReleaseRequestAnnouncer implements ReleaseRequestAnnouncer {

  /** One announcement, field for field as the port declares it — this IS the wire contract. */
  public record Announced(
      String projectId,
      String repoId,
      String repoName,
      String releaseRequestId,
      String backingBranch,
      String mergedSha,
      Instant changedAt) {}

  private final List<Announced> announced = Collections.synchronizedList(new ArrayList<>());

  public List<Announced> announced() {
    return List.copyOf(announced);
  }

  /** Only the announcements about one request — a shared trigger touches several. */
  public List<Announced> announcedFor(String releaseRequestId) {
    return announced().stream()
        .filter(event -> event.releaseRequestId().equals(releaseRequestId))
        .toList();
  }

  public void reset() {
    announced.clear();
  }

  @Override
  public void onReleaseRequestChanged(
      String projectId,
      String repoId,
      String repoName,
      String releaseRequestId,
      String backingBranch,
      String mergedSha,
      Instant changedAt) {
    announced.add(
        new Announced(
            projectId, repoId, repoName, releaseRequestId, backingBranch, mergedSha, changedAt));
  }
}
