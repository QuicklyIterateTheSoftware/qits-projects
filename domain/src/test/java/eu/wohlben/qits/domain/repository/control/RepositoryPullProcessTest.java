package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.process.dto.TechnicalProcessFrame;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The streamed pull ({@code beginPullRepository}): registers a repository-scoped technical process,
 * runs the recursive submodule walk off-thread, and the process's replay tells the whole story —
 * one segment per repository visited, the diamond deduped to a single segment, cycles terminating
 * without reopening, a child failure settling {@code failed} while the walk continues (and {@code
 * done failed} overall), a root divergence failing the process, and the fast-forward verdict + the
 * {@code .qits-config.yml} re-ingestion line landing in the segment. Pull runs host-side against
 * the bare origins, so no docker is needed.
 */
@QuarkusTest
@TestProfile(RepositoryPullProcessTest.TestProfile.class)
public class RepositoryPullProcessTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-pull-process-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject RepositoryRepository repositoryRepository;
  @Inject TechnicalProcessRegistry registry;
  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  /** Records a terminal process's full replay (attach on a terminal process replays + done). */
  private static final class Replay implements TechnicalProcess.Listener {
    final List<TechnicalProcessFrame> frames = new ArrayList<>();

    @Override
    public void onFrame(TechnicalProcessFrame frame) {
      frames.add(frame);
    }

    @Override
    public void onDone() {}

    @Override
    public boolean isOpen() {
      return true;
    }
  }

  private String fixture(String name) throws Exception {
    return getClass().getResource("/fixtures/" + name).toURI().getPath();
  }

  /** The repositories in a project keyed by the trailing bare-repo name of their url. */
  private Map<String, Repository> reposByName(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .collect(Collectors.toMap(r -> Path.of(r.url).getFileName().toString(), r -> r));
  }

  private Path originOf(String repoId) {
    return Path.of(dataDir, repoId, "origin");
  }

  private TechnicalProcess awaitTerminal(String processId) throws InterruptedException {
    TechnicalProcess process = registry.find(processId).orElseThrow();
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "process did not reach done in time");
    return process;
  }

  private Replay replayOf(TechnicalProcess process) {
    Replay replay = new Replay();
    process.attach(replay);
    return replay;
  }

  /** The segment names in the order they were opened. */
  private static List<String> segmentOpens(Replay replay) {
    return replay.frames.stream()
        .filter(f -> "segment-open".equals(f.kind()))
        .map(TechnicalProcessFrame::segment)
        .toList();
  }

  private static String settledStatus(Replay replay, String segment) {
    return replay.frames.stream()
        .filter(f -> "segment-settled".equals(f.kind()) && segment.equals(f.segment()))
        .map(TechnicalProcessFrame::status)
        .findFirst()
        .orElseThrow(() -> new AssertionError("segment '" + segment + "' never settled"));
  }

  private static TechnicalProcessFrame doneFrame(Replay replay) {
    return replay.frames.stream()
        .filter(f -> "done".equals(f.kind()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no done frame"));
  }

  private static boolean hasLineContaining(Replay replay, String needle) {
    return replay.frames.stream()
        .anyMatch(f -> "line".equals(f.kind()) && f.line() != null && f.line().contains(needle));
  }

  @Test
  public void pullStreamsOneSegmentPerRepoAndDedupsTheDiamond() throws Exception {
    var project = projectService.create("Pull Diamond", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);
    // Recurse one level so the full diamond exists: super -> {child-a, shared}, child-a -> {shared,
    // grandchild}. The shared child is reached by two edges.
    repositoryService.importDirectSubmodules(
        reposByName(project.id).get("submodule-child-a.git").id);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(superRepo.id)));

    List<String> opens = segmentOpens(replay);
    // One segment per repository, not per edge: super + child-a + grandchild + exactly one for the
    // diamond-shared child (5 without the visited dedup).
    assertEquals(4, opens.size(), "one segment per visited repository, diamond deduped: " + opens);
    assertEquals("pull:submodule-super.git", opens.get(0), "the root repo's segment opens first");
    assertTrue(opens.contains("pull:child-a"), opens.toString());
    assertTrue(opens.contains("pull:grandchild"), opens.toString());
    assertEquals(
        1,
        opens.stream().filter(s -> s.startsWith("pull:shared")).count(),
        "the shared child gets exactly one segment: " + opens);
    // Everything up to date -> every segment ok, done ok.
    for (String segment : opens) {
      assertEquals("ok", settledStatus(replay, segment), segment);
    }
    assertEquals("ok", doneFrame(replay).status());
  }

  @Test
  public void aCycleTerminatesWithoutReopeningASegment() throws Exception {
    var project = projectService.create("Pull Cycle", null);
    var cycleA =
        repositoryService.cloneRepository(fixture("submodule-cycle-a.git"), null, project, true);
    // Close the loop: import cycle-b's submodule (which points back at cycle-a, deduped to the
    // existing row) so the pull recursion actually walks a->b->a.
    repositoryService.importDirectSubmodules(
        reposByName(project.id).get("submodule-cycle-b.git").id);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(cycleA.id)));

    List<String> opens = segmentOpens(replay);
    assertEquals(2, opens.size(), "a->b, then b->a links back to the visited root: " + opens);
    assertEquals("pull:submodule-cycle-a.git", opens.get(0));
    assertEquals("pull:submodule-cycle-b", opens.get(1));
    assertEquals("ok", doneFrame(replay).status());
  }

  @Test
  public void aChildFailureSettlesFailedWhileLaterChildrenStillPull() throws Exception {
    var project = projectService.create("Pull Child Failure", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);
    Map<String, Repository> repos = reposByName(project.id);
    repositoryService.importDirectSubmodules(repos.get("submodule-child-a.git").id);

    // Break the shared child's local mirror so its own pull throws (fetch by URL still works, but
    // rev-parse of the now-missing local ref fails). It is reached by two edges — the visited dedup
    // means it fails once and is not retried under the other edge.
    Repository shared = reposByName(project.id).get("submodule-shared.git");
    git.exec(originOf(shared.id).toFile(), "git", "update-ref", "-d", "refs/heads/main");

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(superRepo.id)));

    List<String> opens = segmentOpens(replay);
    long failedSharedSegments =
        opens.stream()
            .filter(s -> s.startsWith("pull:shared"))
            .filter(s -> "failed".equals(settledStatus(replay, s)))
            .count();
    assertEquals(1, failedSharedSegments, "exactly the shared child's segment fails: " + opens);
    // The root and the other children still pull — the walk degrades loudly, never blocks.
    assertEquals("ok", settledStatus(replay, "pull:submodule-super.git"));
    assertEquals("ok", settledStatus(replay, "pull:child-a"));
    assertEquals("ok", settledStatus(replay, "pull:grandchild"), "a later child still pulls");
    // A failed child segment makes the overall outcome failed.
    assertEquals("failed", doneFrame(replay).status());
  }

  @Test
  public void aRootDivergenceFailsTheProcess() throws Exception {
    var project = projectService.create("Pull Root Divergence", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);
    // Point the local main branch at the divergent `feature` tip: neither is an ancestor of the
    // other, so the pull refuses (manual merge required).
    String featureSha = git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "feature").trim();
    git.exec(originOf(repo.id).toFile(), "git", "update-ref", "refs/heads/master", featureSha);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(repo.id)));

    assertEquals("failed", doneFrame(replay).status());
    assertTrue(hasLineContaining(replay, "diverged"), "the divergence reason lands in the stream");
  }

  @Test
  public void aFastForwardStreamsTheVerdictAndConfigReingestionLine() throws Exception {
    var project = projectService.create("Pull Fast Forward", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);
    // Rewind the local main branch one commit so the remote is strictly ahead -> fast-forward.
    String parentSha = git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master~1").trim();
    git.exec(originOf(repo.id).toFile(), "git", "update-ref", "refs/heads/master", parentSha);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(repo.id)));

    assertEquals("ok", settledStatus(replay, "pull:testing-repo.git"));
    assertEquals("ok", doneFrame(replay).status());
    assertTrue(hasLineContaining(replay, "Fast-forwarded to"), "the fast-forward verdict is shown");
    assertTrue(
        hasLineContaining(replay, "Re-ingested .qits-config.yml"),
        "a main-branch advance re-ingests .qits-config.yml and reports it in the segment");
  }

  @Test
  public void anUnknownRepositoryFailsFastInRequest() {
    assertThrows(NotFoundException.class, () -> repositoryService.beginPullRepository("nope"));
  }

  @Test
  public void aFreshPullReturnsAProcessIdImmediately() throws Exception {
    var project = projectService.create("Pull Fresh", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);

    String processId = repositoryService.beginPullRepository(repo.id);
    assertNotNull(processId);
    Replay replay = replayOf(awaitTerminal(processId));
    assertEquals("ok", settledStatus(replay, "pull:testing-repo.git"));
    assertTrue(hasLineContaining(replay, "Already up to date"));
    assertEquals("ok", doneFrame(replay).status());
  }
}
