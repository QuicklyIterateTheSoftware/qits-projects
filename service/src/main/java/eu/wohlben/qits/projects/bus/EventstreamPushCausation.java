package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.projects.control.PushCausation;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * {@link PushCausation} over the event bus's ambient cause — the whole of this repo's producer half
 * of the causation chain.
 *
 * <p>It lives in {@code bus/} for the reason every adapter here lives where it does: the port is in
 * {@code domain/…/control}, the implementation is where the framework is, and {@code domain} stays
 * free of {@code eu.wohlben.qits.eventstream}. Three lines, and the interesting part is entirely in
 * what fills {@link CausationScope} rather than in this class:
 *
 * <ul>
 *   <li>an inbound request carrying {@code X-Qits-Causation-Id} — the bus jar's own server filter
 *       establishes the scope for the resource method, so a repository created as part of a release
 *       pushes under the release's cause with nothing said here;
 *   <li>a durable frame being dispatched — {@code onFrame} runs inside the arriving event's scope,
 *       so anything a listener pushes is caused by that event.
 * </ul>
 *
 * <p>What it is <b>not</b> is a way to stamp a backup push. Those go to an external forge through
 * the domain's own {@code git} invocation and never through {@code RepoMirror}, which is where the
 * header is attached — GitHub has no interest in this platform's causes and the header would be an
 * outbound leak of internal identifiers.
 */
@ApplicationScoped
public class EventstreamPushCausation implements PushCausation {

  @Override
  public String currentCauseId() {
    UUID cause = CausationScope.current();
    return cause == null ? null : cause.toString();
  }
}
