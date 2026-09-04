package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseAnnouncer;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's {@link ReleaseAnnouncer}: records the {@code SCMRelease} a landed tag would publish.
 * An ordinary bean over the {@code @DefaultBean} {@code bus/SCMReleaseAnnouncer}, so it wins the
 * port's injection and no test reaches the bus; state through methods, the package convention.
 */
@ApplicationScoped
public class RecordingReleaseAnnouncer implements ReleaseAnnouncer {

  /** The five payload fields, plus the time the envelope would carry. */
  public record Announced(
      String projectId,
      String repoId,
      String repoName,
      String branch,
      String version,
      Instant occurredAt) {}

  private final List<Announced> announced = Collections.synchronizedList(new ArrayList<>());

  public List<Announced> announced() {
    return List.copyOf(announced);
  }

  public void reset() {
    announced.clear();
  }

  @Override
  public void onReleased(
      String projectId,
      String repoId,
      String repoName,
      String branch,
      String version,
      Instant occurredAt) {
    announced.add(new Announced(projectId, repoId, repoName, branch, version, occurredAt));
  }
}
