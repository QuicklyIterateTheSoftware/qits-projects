package eu.wohlben.qits.projects.refinementhost;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The one-liner producers call to announce a refinement change — {@code fireAsync}, so firing never
 * blocks or fails the mutating request; the hint is a courtesy to open browsers. The refinement
 * twin of {@code api/ProjectChangePublisher}.
 */
@ApplicationScoped
public class RefinementChangePublisher {

  @Inject Event<RefinementChangeHint> event;

  public void fire(Long refinementId, RefinementChangeHint.Topic topic) {
    event.fireAsync(new RefinementChangeHint(refinementId, topic));
  }
}
