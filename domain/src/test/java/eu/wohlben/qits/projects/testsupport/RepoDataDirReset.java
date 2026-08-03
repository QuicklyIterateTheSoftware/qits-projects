package eu.wohlben.qits.projects.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Wipes and recreates the shared test data directories once before each test class: {@code
 * qits.repositories.data-dir} ({@code target/qits-test-repos}, still read by the not-yet-migrated
 * read/sync cluster), {@code qits.projects.data-dir} ({@code target/qits-test-projects}, the mirror
 * root), and the fake git host's root ({@code target/qits-test-host}, {@link
 * eu.wohlben.qits.projects.control.FakeGitHostAddress}).
 *
 * <p>Repository tests used to give each class its own {@code Files.createTempDirectory()} path via
 * a per-class {@code @TestProfile}. That made every class's Quarkus config unique, which forced a
 * full Quarkus app restart per class (fresh classloader + CDI container + H2 + all Flyway
 * migrations) — dozens of restarts accumulating classloader/metaspace in the single reused surefire
 * fork until it blew the memory limit. Pointing every class at these stable dirs lets them share a
 * single Quarkus app; this extension restores the per-class clean-slate the temp dirs used to give.
 *
 * <p>Auto-registered for the whole module via {@code META-INF/services} + {@code
 * junit.jupiter.extensions.autodetection.enabled=true}, so tests only had to drop their profile —
 * no per-class annotation. Wiping before every class (including the ones that never touch a dir) is
 * cheap and harmless. A class that overrides one of these three keys via its own {@code
 * @TestProfile} (e.g. {@code SelfSeedServiceTest}) still gets a clean fake host, since {@link
 * eu.wohlben.qits.projects.control.FakeGitHostAddress}'s root is fixed rather than config-driven.
 */
public class RepoDataDirReset implements BeforeAllCallback {

  /**
   * Must match {@code qits.repositories.data-dir} in {@code
   * src/test/resources/application.properties}.
   */
  static final Path DATA_DIR = Path.of("target", "qits-test-repos");

  /** Must match {@code qits.projects.data-dir} in the same file. */
  static final Path PROJECTS_DATA_DIR = Path.of("target", "qits-test-projects");

  /** Must match {@code FakeGitHostAddress.ROOT}. */
  static final Path FAKE_HOST_ROOT = Path.of("target", "qits-test-host");

  @Override
  public void beforeAll(ExtensionContext context) throws IOException {
    for (Path dir : List.of(DATA_DIR, PROJECTS_DATA_DIR, FAKE_HOST_ROOT)) {
      deleteRecursively(dir);
      Files.createDirectories(dir);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(RepoDataDirReset::deleteQuietly);
    }
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      // A concurrent process (e.g. a workspace container mount) may hold a child path; leaving a
      // stale entry is harmless — the next test creates its own repo ids under the wiped root.
    }
  }
}
