package eu.wohlben.qits.projects.control;

/**
 * What caused the work happening on this thread, for a push about to leave for the git host.
 *
 * <p>The git host publishes an SCM event per ref a push moves, and it publishes them under whatever
 * {@code X-Qits-Causation-Id} the push carried. So a chain — a release, the push it makes, the
 * commit event, the CI run, the deployment — stays one chain only if the producer says what it was
 * doing. This port is that sentence, kept as a port for the reason every other reach out of {@code
 * domain} is one: the answer comes from {@code qits-eventstream}'s {@code CausationScope}, and
 * {@code domain} does not know the event bus exists.
 *
 * <p><b>Absent is a supported configuration.</b> With no implementation every push simply names no
 * cause, which is exactly what happened before the git host published anything — so a deployment
 * with the bus dark, and this module's own suite, behave as they always did. The one implementation
 * is {@code service/…/bus/EventstreamPushCausation}.
 *
 * <p>It answers a {@code String} rather than a {@code UUID} because {@code
 * qits-projects-gitmirror} — which is what finally writes it onto the request — depends on nothing
 * at all and takes a plain {@code Supplier<String>}. It re-parses the value before sending it, so a
 * malformed answer costs the push its causation edge and nothing else.
 */
public interface PushCausation {

  /** The id of the event this thread is running because of, or {@code null} when there is none. */
  String currentCauseId();
}
