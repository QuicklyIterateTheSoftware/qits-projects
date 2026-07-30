package eu.wohlben.qits.projects.control;

/**
 * "A project now exists" — announced so that whoever owns deployment can give it its standing
 * target. In this platform that is qits-cd creating the project's {@code main} environment
 * (main-environment-plan.md §1); this context neither knows nor asks what happens next.
 *
 * <p><strong>A port, not an implementation</strong>, and optional like every port here: a
 * deployment that serves projects, repositories and epics with nothing continuously deploying them
 * is a real one. With no implementation present a project is simply created and no environment
 * appears — nothing degrades, because there was nothing deploying it.
 *
 * <p><b>Fire-and-forget, and that is a contract on both sides.</b> Called by {@code
 * ProjectService.create} <em>after</em> the creating transaction commits, so an implementation that
 * reads the project back sees it; and an implementation that throws is logged and swallowed,
 * because a project must never fail to exist because a sibling service was down. It is deliberately
 * <b>not</b> an ordering precondition, unlike {@link WorkspaceLifecycle#createMainWorkspace} — the
 * project is complete without the environment, and the announcement is somebody else's outcome.
 *
 * <p>Ids and values only, never entities: what crosses this seam is what the receiver needs to name
 * the thing, and an entity would make the receiver a reader of this context's schema.
 */
public interface ProjectEnvironmentNotifier {

  /**
   * The project was created. {@code slug} is the identity a receiver should key on — it is
   * immutable and already in the dns-label charset an environment name has to be, which {@code
   * name}, being free-form and editable, is neither.
   */
  void onProjectCreated(String projectId, String name, String slug);
}
