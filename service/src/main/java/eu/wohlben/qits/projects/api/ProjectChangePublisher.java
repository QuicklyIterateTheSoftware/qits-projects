package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.api.ProjectChangeHint.Topic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The one-liner producers call to announce a project change. Wraps CDI {@link Event#fireAsync} so
 * firing never blocks or fails the mutating request: the hint is a courtesy to open browsers, and
 * an epic must not fail to be written because nobody was listening.
 *
 * <p>Async is also what keeps the observer off the caller's thread — {@link
 * ProjectEventBroadcaster} debounces and pushes into live SSE subscriptions, neither of which
 * belongs inside a REST or MCP transaction.
 */
@ApplicationScoped
public class ProjectChangePublisher {

  @Inject Event<ProjectChangeHint> event;

  /** Announce a change on {@code projectId}'s channel. */
  public void fire(String projectId, Topic topic) {
    event.fireAsync(new ProjectChangeHint(projectId, topic));
  }
}
