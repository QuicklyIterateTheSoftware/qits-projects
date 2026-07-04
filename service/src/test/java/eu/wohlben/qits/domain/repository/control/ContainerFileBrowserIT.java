package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.domain.repository.control.WorktreeFileAccess.Entry;
import eu.wohlben.qits.domain.repository.control.WorktreeFileAccess.EntryType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real-docker integration test for {@link ContainerFileAccess} — the {@code docker exec}-backed
 * file browser primitives against an actual docker engine and the {@code qits/workspace} image.
 * Proves the tree listing matches the container's working tree, an uncommitted exec-made edit is
 * visible, and a binary file round-trips byte-identical through the raw-stdout {@code cat} read.
 *
 * <p>Part of the <strong>extended</strong> suite: skipped by every default build, run with {@code
 * ./mvnw verify -Pextended}. Self-skips when docker or the image is absent. A plain JUnit test
 * wiring the real {@link DockerExecutor} into {@link ContainerFileAccess} (not
 * {@code @QuarkusTest}) so the {@code FakeContainerRuntime @Mock} does not shadow the real runtime.
 *
 * <p>Build the image first: {@code docker build -t qits/workspace docker/workspace}.
 */
@Tag("extended")
public class ContainerFileBrowserIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String RUNTIME =
      System.getProperty("qits.workspace.container-runtime", "docker");

  private DockerExecutor executor() {
    DockerExecutor de = new DockerExecutor();
    de.runtime = RUNTIME;
    de.image = IMAGE;
    return de;
  }

  private boolean dockerAndImageAvailable() {
    try {
      Process ping = new ProcessBuilder(RUNTIME, "image", "inspect", IMAGE).start();
      return ping.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static Entry find(List<Entry> entries, String path) {
    return entries.stream().filter(e -> e.path().equals(path)).findFirst().orElseThrow();
  }

  @Test
  public void listsStatsAndReadsTheContainerWorkingTree() throws Exception {
    DockerExecutor de = executor();
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");

    ContainerFileAccess access = new ContainerFileAccess();
    access.containers = de;

    String repoId = UUID.randomUUID().toString();
    String worktreeId = "it-files";
    String container = de.containerName(worktreeId, repoId);
    de.rm(container);
    try {
      de.run(repoId, worktreeId, "it-branch", "main");

      // Lay out a working tree via exec: a git repo, a tracked-ish text file, a nested subdir, and
      // a
      // binary file with an embedded NUL byte.
      exec(de, container, "git init -q");
      exec(de, container, "printf 'hello\\n' > readme.md");
      exec(de, container, "mkdir -p dir1/nested");
      exec(de, container, "printf 'a\\n' > dir1/a.txt");
      exec(de, container, "printf 'b\\n' > dir1/nested/b.txt");
      exec(de, container, "printf 'x\\000y' > blob.bin");

      // stat: types and sizes, symlinks unfollowed, missing reported as MISSING.
      Entry readme = access.stat(repoId, worktreeId, "readme.md");
      assertEquals(EntryType.FILE, readme.type());
      assertEquals(6, readme.size(), "readme.md is 'hello\\n' = 6 bytes");
      assertEquals(EntryType.DIRECTORY, access.stat(repoId, worktreeId, "dir1").type());
      assertEquals(EntryType.MISSING, access.stat(repoId, worktreeId, "nope.txt").type());

      // list one level deep, with the nested subdir's child count.
      List<Entry> listing = access.list(repoId, worktreeId, "dir1");
      assertEquals(EntryType.FILE, find(listing, "dir1/a.txt").type());
      Entry nested = find(listing, "dir1/nested");
      assertEquals(EntryType.DIRECTORY, nested.type());
      assertEquals(1, nested.childCount(), "dir1/nested holds one file");

      // childCount: immediate children of dir1 (a.txt + nested).
      assertEquals(2, access.childCount(repoId, worktreeId, "dir1"));

      // git: the untracked working-tree files show up (nothing committed yet).
      String untracked =
          access.git(repoId, worktreeId, "ls-files", "--others", "--exclude-standard");
      assertTrue(untracked.contains("readme.md"), "untracked readme.md listed: " + untracked);

      // read: text is exact including the trailing newline the String exec path would strip.
      assertArrayEquals(
          "hello\n".getBytes(StandardCharsets.UTF_8), access.read(repoId, worktreeId, "readme.md"));

      // binary round-trips byte-identical, NUL and all.
      assertArrayEquals(new byte[] {'x', 0, 'y'}, access.read(repoId, worktreeId, "blob.bin"));

      // an uncommitted exec-made edit is visible on the next read (the whole point of exec reads).
      exec(de, container, "printf 'changed\\n' > readme.md");
      assertArrayEquals(
          "changed\n".getBytes(StandardCharsets.UTF_8),
          access.read(repoId, worktreeId, "readme.md"));
    } finally {
      de.rm(container);
      assertTrue(!de.exists(container), "container should be removed");
    }
  }

  private void exec(DockerExecutor de, String container, String script) {
    ContainerRuntime.ExecResult r =
        de.exec(container, "/workspace", Map.of(), "bash", "-lc", script);
    assertEquals(0, r.exitCode(), "setup command failed: " + script + "\n" + r.output());
  }
}
