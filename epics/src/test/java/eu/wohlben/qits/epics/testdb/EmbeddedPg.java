package eu.wohlben.qits.epics.testdb;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One real PostgreSQL for this module's whole surefire JVM.
 *
 * <p><b>Why not a container.</b> The first rule of this repo is that a clone builds and tests green
 * with no docker, and both stores are postgres now — so Testcontainers and Quarkus dev services are
 * both out, and the schema still has to be exercised against the database it actually ships on.
 * Zonky resolves real postgres binaries as ordinary Maven artifacts and this class spawns them as a
 * child process: a dependency, not a daemon.
 *
 * <p><b>This is a COPY of {@code domain}'s, deliberately.</b> Sharing it would mean a test-jar
 * dependency on a module this one has none on — the whole reason {@code epics} is lift-out-able is
 * that it depends on neither {@code domain} nor any auth module, and a test-scoped edge is still an
 * edge. The two copies must stay identical in behaviour; what they must NOT share is the database
 * NAME, because every (module, datasource) pair names its own so two suites cannot mean the same
 * schema.
 *
 * <p><b>The instance is tracked in a system property, not in this static field alone.</b> A Quarkus
 * test run loads config sources in more than one classloader, so a second copy of this class is
 * loaded with its own statics; the property is the one thing they share, and it is what keeps the
 * count at one postgres per JVM instead of one per classloader.
 */
public final class EmbeddedPg {

  /** Zonky's superuser. Its authentication is {@code trust}, so the password below is a placeholder. */
  public static final String USER = "postgres";

  /** Any string does: the embedded instance trusts local connections. Never a real credential. */
  public static final String PASSWORD = "embedded";

  /** Where the running instance's port is published for the other classloaders. */
  private static final String PORT_PROPERTY = "qits.test.embedded-pg.port";

  private static EmbeddedPostgres started;

  private EmbeddedPg() {}

  /** The port the one embedded instance listens on, starting it on the first call. */
  public static synchronized int port() {
    String recorded = System.getProperty(PORT_PROPERTY);
    if (recorded != null) {
      return Integer.parseInt(recorded);
    }
    try {
      started = EmbeddedPostgres.builder().start();
    } catch (Exception e) {
      throw new IllegalStateException("could not start the embedded postgres", e);
    }
    System.setProperty(PORT_PROPERTY, String.valueOf(started.getPort()));
    Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedPg::stop, "embedded-pg-stop"));
    return started.getPort();
  }

  /** A JDBC url for the named database on the embedded instance, creating it if it is new. */
  public static synchronized String url(String database) {
    String url = "jdbc:postgresql://localhost:" + port() + "/" + database;
    ensureDatabase(database);
    return url;
  }

  private static void ensureDatabase(String database) {
    String adminUrl = "jdbc:postgresql://localhost:" + port() + "/postgres";
    try (Connection admin = DriverManager.getConnection(adminUrl, USER, PASSWORD);
        Statement sql = admin.createStatement()) {
      try (ResultSet found =
          sql.executeQuery("select 1 from pg_database where datname = '" + database + "'")) {
        if (found.next()) {
          return;
        }
      }
      sql.execute("create database " + database);
    } catch (Exception e) {
      throw new IllegalStateException("could not create the test database " + database, e);
    }
  }

  private static synchronized void stop() {
    if (started != null) {
      try {
        started.close();
      } catch (Exception e) {
        // A JVM on its way out; a postgres that outlives it by a moment is not worth a stack trace.
      }
      started = null;
    }
  }
}
