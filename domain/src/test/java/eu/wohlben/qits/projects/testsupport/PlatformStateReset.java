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
import java.nio.file.DirectoryNotEmptyException;
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
      // agent_credential is in the list even though it has no relation to the other three: a row
      // one test commissioned would otherwise still be there for the next, and the credential
      // reconcile reads the whole table.
      sql.execute("truncate table repository_name, repository, project, agent_credential");
    } catch (SQLException e) {
      throw new IllegalStateException("Could not reset the projects tables", e);
    }
    wipe(
        Path.of(
            ConfigProvider.getConfig().getValue("qits.projects.data-dir", String.class)));
    wipe(RepoDataDirReset.FAKE_HOST_ROOT);
  }

  /**
   * Empty {@code dir}, and try again when something wrote into it while we walked.
   *
   * <p><b>The retry is not defensive padding; it is the one failure mode this walk has.</b> A
   * backup is debounced ({@code qits.projects.backup.debounce-ms}, two seconds), so a push a test
   * asked for lands on a timer that fires during a <em>later</em> test — inside a mirror this method
   * is in the middle of deleting. {@link Files#walk} takes its list first, so a file created after
   * the enumeration leaves its parent non-empty when the delete reaches it, and the whole suite
   * fails at whichever method happened to be next. Deleting again finds the file the writer left.
   */
  private static void wipe(Path dir) {
    for (int attempt = 1; Files.exists(dir); attempt++) {
      try (Stream<Path> walk = Files.walk(dir)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          if (!p.equals(dir)) {
            Files.deleteIfExists(p);
          }
        }
        break;
      } catch (DirectoryNotEmptyException raced) {
        if (attempt == 5) {
          throw new UncheckedIOException("Could not wipe " + dir, raced);
        }
        pause();
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

  /** Long enough for a debounced push to finish writing, short enough to be invisible. */
  private static void pause() {
    try {
      Thread.sleep(200);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted wiping the platform state", e);
    }
  }
}
