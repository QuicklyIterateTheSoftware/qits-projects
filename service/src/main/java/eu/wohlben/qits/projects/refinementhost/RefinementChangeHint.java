package eu.wohlben.qits.projects.refinementhost;

/**
 * A payload-free "something changed, re-read it" signal for one refinement's live channel — the
 * refinement flavour of {@code api/ProjectChangeHint}, keyed by the refinement row id and carrying
 * the topics the refining route actually subscribes to.
 *
 * <p>The hint carries no data: the frontend reacts by re-fetching through the unchanged REST and
 * daemon-proxy endpoints, so a dropped or missed hint self-heals on the next hint or on reconnect.
 *
 * <p>The wire name is the enum constant lowercased with {@code _} → {@code -} — the same contract
 * the workspaces channel established, which is what lets the SPA's topic set move over unchanged.
 */
public record RefinementChangeHint(Long refinementId, Topic topic) {

  /** The kind of change; maps 1:1 to a frontend query invalidation. */
  public enum Topic {
    /** A coding agent's live activity changed — re-read the row and the agents panel. */
    AGENT_ACTIVITY,
    /** The working tree flipped between clean and dirty — re-read the row. */
    GIT_STATUS,
    /** A technical process began or settled — re-read the active process. */
    PROCESS,
    /** The checkout's files changed — re-read the files panel. */
    FILES,
    /** The daemon's command list changed — re-read the agents/chat surfaces. */
    COMMANDS,
    /** The host-held prompt draft changed — re-read it. */
    PROMPT_DRAFT,
    /** The host-held prompt attachments changed — re-read the list. */
    PROMPT_ATTACHMENTS
  }
}
