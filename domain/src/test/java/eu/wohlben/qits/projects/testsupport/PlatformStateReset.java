package eu.wohlben.qits.projects.testsupport;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * A clean platform state before <b>every test method</b>: empty projects tables, an empty mirror
 * root and an empty fake git host.
 *
 * <p>A repository's id is its addressable name now — deterministic, never a fresh UUID — so two
 * tests that clone the same fixture ({@code testing-repo.git}, {@code qits-qits.git}, …) mint the
 * same id, and the second one fails the name-collision check that guards the invariant. The rows
 * and bares a test leaves behind are exactly such collisions waiting for the next method, which is
 * why {@link RepoDataDirReset}'s per-class wipe is no longer enough. Tests stay as they are; the
 * slate is what resets.
 *
 * <p>A Quarkus callback rather than a JUnit extension because the reset needs the running
 * application: the datasource bean for the truncate, and the live config for the mirror root (a
 * {@code @TestProfile} may point {@code qits.projects.data-dir} at its own directory). Registered
 * via {@code META-INF/services}, and carried to {@code service}'s suite in this module's test-jar
 * like the rest of {@code testsupport}. Under a {@code @QuarkusIntegrationTest} there is no
 * container in this JVM and the launched process owns its own state, so the callback stands down.
 */
public class PlatformStateReset implements QuarkusTestBeforeEachCallback {

  @Override
  public void beforeEach(QuarkusTestMethodContext context) {
    ArcContainer arc = Arc.container();
    if (arc == null || !arc.isRunning()) {
      return; // @QuarkusIntegrationTest: the app under test runs in its own process
    }
    InstanceHandle<AgroalDataSource> dataSource =
        arc.instance(AgroalDataSource.class, new DataSource.DataSourceLiteral("projects"));
    if (!dataSource.isAvailable()) {
      return;
    }
    try (Connection connection = dataSource.get().getConnection();
        Statement sql = connection.createStatement()) {
      sql.execute("truncate table repository_name, repository, project");
    } catch (SQLException e) {
      throw new IllegalStateException("Could not reset the projects tables", e);
    }
    wipe(
        Path.of(
            ConfigProvider.getConfig().getValue("qits.projects.data-dir", String.class)));
    wipe(RepoDataDirReset.FAKE_HOST_ROOT);
  }

  private static void wipe(Path dir) {
    if (Files.exists(dir)) {
      try (Stream<Path> walk = Files.walk(dir)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          if (!p.equals(dir)) {
            Files.deleteIfExists(p);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Could not wipe " + dir, e);
      }
    }
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not recreate " + dir, e);
    }
  }
}
