package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.project.control.ProjectService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Verifies the resolve-merge-conflict composed action against a real diverged-with-conflict
 * repository cloned from the fixture: a worktree and its parent both change the same new file.
 */
@QuarkusTest
@TestProfile(ResolveConflictServiceTest.TestProfile.class)
public class ResolveConflictServiceTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-resolve-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject WorktreeService worktreeService;

  @Inject ResolveConflictService resolveConflictService;

  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  /** Clones the fixture and builds a conflict on {@code feat} vs its parent {@code master}. */
  private String divergedRepo() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Resolve Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);

    // feat forks from master (the fixture's default branch, which gets a main worktree on clone).
    worktreeService.createWorktree(repo.id, "feat", "master", "feat");

    // Both branches add the same new file with different content -> an add/add conflict.
    commitFile(repo.id, "master", "conflict.txt", "from master\n");
    commitFile(repo.id, "feat", "conflict.txt", "from feat\n");
    return repo.id;
  }

  private void commitFile(String repoId, String worktreeId, String name, String content)
      throws Exception {
    Path worktree = Path.of(dataDir, repoId, "worktrees", worktreeId);
    Files.writeString(worktree.resolve(name), content, StandardCharsets.UTF_8);
    git.exec(worktree.toFile(), "git", "add", name);
    git.exec(
        worktree.toFile(),
        "git",
        "-c",
        "user.email=qits@local",
        "-c",
        "user.name=qits",
        "commit",
        "-m",
        "change " + name + " on " + worktreeId);
  }

  @Test
  public void listsTheConflictingFileNames() throws Exception {
    String repoId = divergedRepo();

    var files = resolveConflictService.listConflictingFiles(repoId, "feat");

    assertTrue(files.contains("conflict.txt"), "should report the conflicting file: " + files);
  }

  @Test
  public void resolveForksAWorktreeAndWritesThePrompt() throws Exception {
    String repoId = divergedRepo();

    var result = resolveConflictService.resolveConflict(repoId, "feat");

    assertEquals("feat-resolve", result.worktreeId());
    assertEquals("feat-resolve", result.branch());
    assertNotNull(result.actionId());

    // The resolution worktree exists on disk, forked off feat (so it carries our work).
    Path resolutionWorktree = Path.of(dataDir, repoId, "worktrees", "feat-resolve");
    assertTrue(Files.exists(resolutionWorktree), "resolution worktree should be checked out");

    // It targets the ORIGINAL parent (master), not the branch it forked off, so integrating it
    // lands the work in master and leaves feat cleanable.
    String resolutionParent =
        worktreeService.listWorktrees(repoId).stream()
            .filter(w -> "feat-resolve".equals(w.worktreeId()))
            .findFirst()
            .orElseThrow()
            .parent();
    assertEquals("master", resolutionParent);

    // The composed prompt is written for Claude, naming the parent and the divergence.
    Path prompt = resolutionWorktree.resolve(".qits/resolve-prompt.md");
    assertTrue(Files.exists(prompt), "prompt file should be written");
    String text = Files.readString(prompt);
    assertTrue(text.contains("diverged"), text);
    assertTrue(text.contains("master"), "prompt should name the parent: " + text);
    assertTrue(text.contains("Merge `master`"), "prompt should instruct the merge: " + text);
  }

  @Test
  public void resolveActionIsReusedAcrossInvocations() throws Exception {
    String repoId = divergedRepo();

    var first = resolveConflictService.resolveConflict(repoId, "feat");
    // A second resolution (e.g. another branch) reuses the one repository-owned action.
    worktreeService.createWorktree(repoId, "feat2", "master", "feat2");
    commitFile(repoId, "feat2", "other.txt", "feat2\n");
    var second = resolveConflictService.resolveConflict(repoId, "feat2");

    assertEquals(first.actionId(), second.actionId(), "the resolve action should be reused");
    assertFalse(first.worktreeId().equals(second.worktreeId()), "each gets its own worktree");
  }
}
