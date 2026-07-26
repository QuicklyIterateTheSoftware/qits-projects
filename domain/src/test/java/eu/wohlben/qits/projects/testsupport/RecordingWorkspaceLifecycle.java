package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.WorkspaceLifecycle;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * A TEST-SCOPE implementation of the {@link WorkspaceLifecycle} port that records the calls instead
 * of provisioning anything.
 *
 * <p>What it preserves: the monorepo's suite asserted "the superproject gets a default main
 * workspace, an imported submodule child does not" and "a project wrapper starts on a workspace" by
 * querying the {@code workspace} table. That table is qits-workspaces' now, so the assertion is
 * re-expressed against the seam itself — which repositories this context ASKS for a main workspace —
 * and proves the same rule (see {@code RepositoryService.cloneRepository}'s {@code
 * createMainWorkspace} flag). Nothing in {@code src/main} references this class.
 */
@ApplicationScoped
public class RecordingWorkspaceLifecycle implements WorkspaceLifecycle {

  private final List<String> mainWorkspacesCreatedFor = new ArrayList<>();
  private final List<String> repositoriesReleased = new ArrayList<>();

  @Override
  public synchronized void createMainWorkspace(String repoId, String branch) {
    mainWorkspacesCreatedFor.add(repoId);
  }

  @Override
  public synchronized void releaseRepository(String repoId) {
    repositoriesReleased.add(repoId);
  }

  public synchronized boolean createdMainWorkspaceFor(String repoId) {
    return mainWorkspacesCreatedFor.contains(repoId);
  }

  public synchronized boolean releasedRepository(String repoId) {
    return repositoriesReleased.contains(repoId);
  }
}
