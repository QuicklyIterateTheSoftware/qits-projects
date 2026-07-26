package eu.wohlben.qits.projects.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Wipes and recreates the shared test repositories directory ({@code qits.repositories.data-dir},
 * fixed at {@code target/qits-test-repos} for the whole suite) once before each test class.
 *
 * <p>Repository tests used to give each class its own {@code Files.createTempDirectory()} path via
 * a per-class {@code @TestProfile}. That made every class's Quarkus config unique, which forced a
 * full Quarkus app restart per class (fresh classloader + CDI container + H2 + all Flyway
 * migrations) — dozens of restarts accumulating classloader/metaspace in the single reused surefire
 * fork until it blew the memory limit. Pointing every class at one stable data-dir lets them share
 * a single Quarkus app; this extension restores the per-class clean-slate the temp dirs used to
 * give.
 *
 * <p>Auto-registered for the whole module via {@code META-INF/services} + {@code
 * junit.jupiter.extensions.autodetection.enabled=true}, so tests only had to drop their profile —
 * no per-class annotation. Wiping before every class (including the ones that never touch the dir)
 * is cheap and harmless.
 */
public class RepoDataDirReset implements BeforeAllCallback {

  /**
   * Must match {@code qits.repositories.data-dir} in {@code
   * src/test/resources/application.properties}.
   */
  static final Path DATA_DIR = Path.of("target", "qits-test-repos");

  @Override
  public void beforeAll(ExtensionContext context) throws IOException {
    deleteRecursively(DATA_DIR);
    Files.createDirectories(DATA_DIR);
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
