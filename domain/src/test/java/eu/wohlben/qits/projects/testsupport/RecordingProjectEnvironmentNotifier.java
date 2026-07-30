package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.ProjectEnvironmentNotifier;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A TEST-SCOPE implementation of the {@link ProjectEnvironmentNotifier} port that records the
 * announcement instead of creating anything.
 *
 * <p>The seam, not the wire: "did this context announce the project, with which slug" is what
 * belongs here, because the environment row it leads to is qits-cd's and lives in another service's
 * database. What actually leaves the process — method, path, payload, 409 tolerance — is pinned by
 * {@code CdEnvironmentNotifierTest} against a local server. Nothing in {@code src/main} references
 * this class.
 */
@ApplicationScoped
public class RecordingProjectEnvironmentNotifier implements ProjectEnvironmentNotifier {

  /** One announcement, exactly as the port delivered it. */
  public record Announcement(String projectId, String name, String slug) {}

  private final List<Announcement> announcements = new ArrayList<>();

  @Override
  public synchronized void onProjectCreated(String projectId, String name, String slug) {
    announcements.add(new Announcement(projectId, name, slug));
  }

  public synchronized List<Announcement> announcements() {
    return List.copyOf(announcements);
  }

  public synchronized Optional<Announcement> announcementFor(String projectId) {
    return announcements.stream().filter(a -> a.projectId().equals(projectId)).findFirst();
  }

  /**
   * Forgets everything recorded so far. {@code @QuarkusTest} shares one application — and therefore
   * one instance of this bean — across a class, so a test that counts announcements has to start
   * from a known state.
   */
  public synchronized void clear() {
    announcements.clear();
  }
}
