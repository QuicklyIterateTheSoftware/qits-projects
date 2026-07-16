package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real-docker integration test that a superproject's submodules <em>materialize offline</em> the
 * way {@link WorkspaceService#provisionContainer} wires them: after the plain clone, each level of
 * the walk overrides its {@code submodule.<name>.url}s to the child repositories' own urls and runs
 * one non-recursive {@code submodule update --init} — then repeats the same sequence inside every
 * checked-out child that has submodules of its own (git's {@code --recursive} cannot be used: it
 * derives nested urls from each child's {@code .gitmodules}, which no override reaches).
 *
 * <p>Part of the <strong>extended</strong> suite (skipped by default, run with {@code ./mvnw verify
 * -Pextended}); self-skips when docker or the {@code qits/workspace} image is absent. Deliberately
 * a plain JUnit test (not {@code @QuarkusTest}) so the {@code FakeContainerRuntime @Mock} doesn't
 * apply.
 *
 * <p>Offline is proven structurally: the container runs on {@code --network none}, so nothing
 * outside it is reachable. The git source is {@code docker cp}'d <em>into</em> the container (not
 * bind-mounted — a bind mount resolves on the daemon's filesystem, which differs from the test's
 * under docker-in-docker) and the children are served over local paths (git's {@code
 * protocol.file.allow=always} lets a submodule clone one) instead of the production HTTP git host —
 * the same override→update sequence and relative-name→id mapping, minus the environment-coupled
 * qits-net wiring (exercised by the app/verify path). Build the image first: {@code docker build -t
 * qits/workspace --target workspace -f docker/qits/Dockerfile .}
 */
@Tag("extended")
public class WorkspaceSubmoduleMaterializationIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String RUNTIME =
      System.getProperty("qits.workspace.container-runtime", "docker");

  private boolean dockerAndImageAvailable() {
    try {
      return new ProcessBuilder(RUNTIME, "image", "inspect", IMAGE).start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  public void submoduleMaterializesOfflineFromItsChildUrl() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");

    // Stage a git source dir: the superproject at super/ and its single leaf submodule at shared/,
    // docker cp'd into the container at /gitsrc. `submodule.lib.url` is overridden to
    // /gitsrc/shared
    // (the local analog of the production child git-host url), exactly as provisionContainer does.
    Path gitsrc = Files.createTempDirectory("qits-it-submodule");
    copyTree(fixture("submodule-simple-super.git"), gitsrc.resolve("super"));
    copyTree(fixture("submodule-shared.git"), gitsrc.resolve("shared"));

    String container = "qits-it-submodule-" + UUID.randomUUID();
    rm(container);
    try {
      // Offline by construction: --network none. The image toolchain (git) is enough.
      run(
          RUNTIME,
          "run",
          "-d",
          "--name",
          container,
          "--network",
          "none",
          "--entrypoint",
          "sleep",
          IMAGE,
          "600");
      // Ship the git source into the container (docker cp works under docker-in-docker; a bind
      // mount
      // would resolve on the daemon's filesystem, not the test's).
      assertEquals(0, exec(container, "mkdir", "-p", "/gitsrc").exit);
      run(RUNTIME, "cp", gitsrc + "/.", container + ":/gitsrc");

      // 1) Plain clone of the superproject (byte-for-byte the historical provision step).
      Exec clone = exec(container, "git", "clone", "--branch", "main", "/gitsrc/super", "/tmp/ws");
      assertEquals(0, clone.exit, "superproject clone must succeed offline: " + clone.output);

      // 2) Override the submodule url to the child's own url, then materialize it — the wiring
      // submoduleWiringCommands emits (protocol.file.allow=always is the IT-local stand-in for the
      // HTTP transport production uses; the sequence is identical).
      assertEquals(
          0,
          exec(container, "git", "-C", "/tmp/ws", "config", "submodule.lib.url", "/gitsrc/shared")
              .exit);
      Exec update =
          exec(
              container,
              "git",
              "-C",
              "/tmp/ws",
              "-c",
              "protocol.file.allow=always",
              "submodule",
              "update",
              "--init",
              "--",
              "lib");
      assertEquals(0, update.exit, "submodule update must succeed offline: " + update.output);

      // 3) The submodule working tree is populated with the child's content.
      Exec content = exec(container, "cat", "/tmp/ws/lib/shared.txt");
      assertEquals(0, content.exit, "submodule file must exist: " + content.output);
      assertTrue(
          content.output.contains("shared content"),
          "the submodule dir must carry the child's content, was: " + content.output);
    } finally {
      rm(container);
      deleteTree(gitsrc);
    }
  }

  /**
   * The depth-2 diamond ({@code super → child-a → {shared, grandchild}} plus {@code super →
   * shared}): the exact shape that fails under a single {@code submodule update --init --recursive}
   * (child-a's own relative urls resolve to un-served names — the prod encounter in
   * docs/issues/resolved/2026-07-16_nested-submodule-clone-fails-workspace-container.md) and that
   * the level-by-level walk materializes: wire+update the super's level, then re-apply the same
   * sequence inside the checked-out child-a.
   */
  @Test
  public void nestedSubmodulesMaterializeLevelByLevel() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");

    Path gitsrc = Files.createTempDirectory("qits-it-submodule-nested");
    copyTree(fixture("submodule-super.git"), gitsrc.resolve("super"));
    copyTree(fixture("submodule-child-a.git"), gitsrc.resolve("child-a"));
    copyTree(fixture("submodule-shared.git"), gitsrc.resolve("shared"));
    copyTree(fixture("submodule-grandchild.git"), gitsrc.resolve("grandchild"));

    String container = "qits-it-submodule-nested-" + UUID.randomUUID();
    rm(container);
    try {
      run(
          RUNTIME,
          "run",
          "-d",
          "--name",
          container,
          "--network",
          "none",
          "--entrypoint",
          "sleep",
          IMAGE,
          "600");
      assertEquals(0, exec(container, "mkdir", "-p", "/gitsrc").exit);
      run(RUNTIME, "cp", gitsrc + "/.", container + ":/gitsrc");

      Exec clone = exec(container, "git", "clone", "--branch", "main", "/gitsrc/super", "/tmp/ws");
      assertEquals(0, clone.exit, "superproject clone must succeed offline: " + clone.output);

      // Level 0: the super's direct edges (child-a + the diamond's direct shared), then one
      // non-recursive update — the sequence submoduleWiringCommands emits for path ".".
      assertEquals(
          0,
          exec(
                  container,
                  "git",
                  "-C",
                  "/tmp/ws",
                  "config",
                  "submodule.child-a.url",
                  "/gitsrc/child-a")
              .exit);
      assertEquals(
          0,
          exec(
                  container,
                  "git",
                  "-C",
                  "/tmp/ws",
                  "config",
                  "submodule.shared.url",
                  "/gitsrc/shared")
              .exit);
      Exec level0 =
          exec(
              container,
              "git",
              "-C",
              "/tmp/ws",
              "-c",
              "protocol.file.allow=always",
              "submodule",
              "update",
              "--init",
              "--",
              "child-a",
              "shared-direct");
      assertEquals(0, level0.exit, "level-0 update must succeed offline: " + level0.output);

      // The checked-out child carries its .git marker — what wireSubmodules' descent guard probes.
      assertEquals(
          0,
          exec(container, "test", "-e", "/tmp/ws/child-a/.git").exit,
          "the checked-out child must carry the .git marker the descent probes for");

      // Level 1: the same sequence rooted at the checked-out child — what wireSubmodules re-emits
      // for path "child-a" from the child's own DB edges.
      assertEquals(
          0,
          exec(
                  container,
                  "git",
                  "-C",
                  "/tmp/ws/child-a",
                  "config",
                  "submodule.shared.url",
                  "/gitsrc/shared")
              .exit);
      assertEquals(
          0,
          exec(
                  container,
                  "git",
                  "-C",
                  "/tmp/ws/child-a",
                  "config",
                  "submodule.grandchild.url",
                  "/gitsrc/grandchild")
              .exit);
      Exec level1 =
          exec(
              container,
              "git",
              "-C",
              "/tmp/ws/child-a",
              "-c",
              "protocol.file.allow=always",
              "submodule",
              "update",
              "--init",
              "--",
              "shared",
              "grandchild");
      assertEquals(0, level1.exit, "level-1 update must succeed offline: " + level1.output);

      // Depth-2 content is populated, and the diamond materializes at BOTH paths.
      for (String file :
          List.of(
              "/tmp/ws/child-a/grandchild/gc.txt",
              "/tmp/ws/child-a/shared/shared.txt",
              "/tmp/ws/shared-direct/shared.txt")) {
        Exec content = exec(container, "cat", file);
        assertEquals(0, content.exit, file + " must exist: " + content.output);
      }
    } finally {
      rm(container);
      deleteTree(gitsrc);
    }
  }

  private Path fixture(String name) throws Exception {
    return Path.of(getClass().getResource("/fixtures/" + name).toURI());
  }

  private record Exec(int exit, String output) {}

  private Exec exec(String container, String... argv) throws Exception {
    List<String> cmd = new ArrayList<>(List.of(RUNTIME, "exec", container));
    cmd.addAll(List.of(argv));
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    p.getInputStream().transferTo(out);
    int exit = p.waitFor();
    return new Exec(exit, out.toString(StandardCharsets.UTF_8).trim());
  }

  private void run(String... argv) throws Exception {
    Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    p.getInputStream().transferTo(out);
    assertEquals(
        0,
        p.waitFor(),
        "command failed: " + String.join(" ", argv) + "\n" + out.toString(StandardCharsets.UTF_8));
  }

  private void rm(String container) {
    try {
      new ProcessBuilder(RUNTIME, "rm", "-f", container)
          .redirectErrorStream(true)
          .start()
          .waitFor();
    } catch (Exception ignored) {
      // best-effort cleanup
    }
  }

  private void copyTree(Path from, Path to) throws Exception {
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path src : (Iterable<Path>) paths::iterator) {
        Path dst = to.resolve(from.relativize(src).toString());
        if (Files.isDirectory(src)) {
          Files.createDirectories(dst);
        } else {
          Files.createDirectories(dst.getParent());
          Files.copy(src, dst);
        }
      }
    }
  }

  private void deleteTree(Path root) throws Exception {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
