package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.RepositoryAnnouncer;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A TEST-SCOPE implementation of the {@link RepositoryAnnouncer} port that records the
 * announcements instead of publishing them.
 *
 * <p>It is what lets a suite assert the one thing about a rename that is not a row: that the
 * platform was told, once, with the coordinates a consumer needs. The bus itself is dark under
 * {@code %test} — {@code QitsEventBus.publish} is a debug log with the switch off — so without this
 * fake "an event was announced" and "nothing happened" would look identical.
 *
 * <p>An ordinary bean and not an {@code @Alternative}: {@code service}'s shipped implementation is
 * {@code @DefaultBean}, so this one wins the port's injection point simply by existing, exactly as
 * {@code FakeContainerRuntime} and {@code FakeAgentCredentials} do. Nothing in {@code src/main}
 * references it.
 */
@ApplicationScoped
public class RecordingRepositoryAnnouncer implements RepositoryAnnouncer {

  /** One announcement, as the port stated it. */
  public record Renamed(
      String projectId, String repositoryId, String oldName, String newName, Instant renamedAt) {}

  private final List<Renamed> renames = new ArrayList<>();

  @Override
  public synchronized void onRepositoryRenamed(
      String projectId, String repositoryId, String oldName, String newName, Instant renamedAt) {
    renames.add(new Renamed(projectId, repositoryId, oldName, newName, renamedAt));
  }

  /** Every announcement about {@code repositoryId}, in the order they were made. */
  public synchronized List<Renamed> renamesOf(String repositoryId) {
    return renames.stream().filter(r -> r.repositoryId().equals(repositoryId)).toList();
  }

  /** The last announcement about {@code repositoryId}, if there was one. */
  public synchronized Optional<Renamed> lastRenameOf(String repositoryId) {
    List<Renamed> mine = renamesOf(repositoryId);
    return mine.isEmpty() ? Optional.empty() : Optional.of(mine.get(mine.size() - 1));
  }

  public synchronized void clear() {
    renames.clear();
  }
}
