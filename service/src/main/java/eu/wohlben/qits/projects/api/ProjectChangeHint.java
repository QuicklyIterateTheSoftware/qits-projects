package eu.wohlben.qits.projects.api;

/**
 * A payload-free "something changed, re-read it" signal for one project's live channel — the
 * projects flavour of qits-workspaces' {@code WorkspaceChangeHint}. Fired at every epic mutation
 * choke-point (the REST controllers and the epic MCP tools) and delivered over CDI async events to
 * {@link ProjectEventBroadcaster}, which pushes the {@link Topic} name to subscribed browsers.
 *
 * <p>The hint carries no data: the frontend reacts by re-fetching through the unchanged REST
 * endpoints, so a dropped or missed hint self-heals on the next hint or on reconnect.
 *
 * <p>It lives in {@code service} rather than in {@code epics} because every producer is here — the
 * epics module stays free of anything the SSE boundary needs, and it depends on this package
 * nowhere.
 */
public record ProjectChangeHint(String projectId, Topic topic) {

  /** The kind of change; maps 1:1 to a frontend query-invalidation. */
  public enum Topic {
    /** An epic, feature or task of this project was created, changed, moved or removed. */
    EPICS,
    /**
     * A per-project refinement agent's live activity changed. Nothing fires it yet — it is on the
     * wire contract from the start so the frontend can subscribe to it before the agent registry
     * exists, rather than needing a second protocol change later.
     */
    AGENT_ACTIVITY
  }
}
