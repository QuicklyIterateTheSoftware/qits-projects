package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.ProjectEnvironmentNotifier;
import eu.wohlben.qits.projects.control.ProjectReconciliation.EnvironmentAssertion;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A TEST-SCOPE implementation of the {@link ProjectEnvironmentNotifier} port that records the
 * announcement instead of creating anything, and answers the synchronous half with whatever the
 * test scripted.
 *
 * <p>The seam, not the wire: "did this context announce the project, with which slug" is what
 * belongs here, because the environment row it leads to is qits-cd's and lives in another service's
 * database. What actually leaves the process — method, path, payload, 409 tolerance, and how a
 * status code becomes an outcome — is pinned by {@code CdEnvironmentNotifierTest} against a local
 * server. Nothing in {@code src/main} references this class.
 *
 * <p><b>{@code @Alternative @Priority}, and that is load-bearing in {@code service}'s suite.</b>
 * There the real {@code CdEnvironmentNotifier} is a bean too, so the port would have two
 * implementations and {@code ProjectReconcileService} — which has one answer to give and so takes
 * the first candidate — would report whichever the container happened to hand it. A global
 * alternative makes this the only implementation, which is what lets a controller test script an
 * outcome and then assert it. In {@code domain}'s own suite there is nothing else to displace.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingProjectEnvironmentNotifier implements ProjectEnvironmentNotifier {

  /** One announcement, exactly as the port delivered it. */
  public record Announcement(String projectId, String name, String slug) {}

  private final List<Announcement> announcements = new ArrayList<>();

  private final List<Announcement> reassertions = new ArrayList<>();

  private EnvironmentAssertion scripted = EnvironmentAssertion.created();

  @Override
  public synchronized void onProjectCreated(String projectId, String name, String slug) {
    announcements.add(new Announcement(projectId, name, slug));
  }

  @Override
  public synchronized EnvironmentAssertion ensureEnvironment(
      String projectId, String name, String slug) {
    reassertions.add(new Announcement(projectId, name, slug));
    return scripted;
  }

  /** What the next {@link #ensureEnvironment} answers. */
  public synchronized void willAnswer(EnvironmentAssertion assertion) {
    this.scripted = assertion;
  }

  public synchronized List<Announcement> announcements() {
    return List.copyOf(announcements);
  }

  public synchronized Optional<Announcement> announcementFor(String projectId) {
    return announcements.stream().filter(a -> a.projectId().equals(projectId)).findFirst();
  }

  /** The synchronous re-assertions a reconcile asked for, in order. */
  public synchronized List<Announcement> reassertions() {
    return List.copyOf(reassertions);
  }

  /**
   * Forgets everything recorded so far and restores the default answer. {@code @QuarkusTest} shares
   * one application — and therefore one instance of this bean — across a class, so a test that
   * counts announcements or scripts an outcome has to start from a known state.
   */
  public synchronized void clear() {
    announcements.clear();
    reassertions.clear();
    scripted = EnvironmentAssertion.created();
  }
}
