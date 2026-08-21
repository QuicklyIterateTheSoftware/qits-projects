package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.JDBCConnectionException;

/**
 * The alias table with a postgres cutover in it: the next reads throw the exception a caller
 * actually sees when its connection dies mid-flight, then the table answers normally again.
 *
 * <p>The failure is the real shape rather than a marker — Hibernate's {@code JDBCConnectionException}
 * wrapping postgres' {@code 57P01} ("terminating connection due to administrator command"), which is
 * what the server says to every open connection while it is being replaced. {@code DbRetry} decides
 * what to retry by walking that cause chain, so a stand-in exception would prove the retry runs and
 * not that it fires on a cutover.
 *
 * <p><b>{@code @Alternative} with no {@code @Priority}</b>: it is enabled by two test profiles
 * (`RepositoryNameCutoverTest.OneLostConnection` and `RepositoryCatalogueTest.DeployedPosture`) and
 * is inert in every other class of this suite. A globally enabled one would sit in the path of every
 * repository read here, armed or not.
 *
 * <p><b>The two reads are armed separately</b> — {@link #loseTheConnection} for name resolution,
 * {@link #loseTheConnectionNaming} for the reverse read the catalogue does — because an unrelated
 * background sweep touching one would otherwise spend the other test's armed failure, and a test
 * whose failure was consumed elsewhere passes while proving nothing.
 */
@Alternative
@ApplicationScoped
public class ConnectionLosingRepositoryNames extends RepositoryNameRepository {

  private final AtomicInteger failuresLeft = new AtomicInteger();

  private final AtomicInteger namingFailuresLeft = new AtomicInteger();

  private final AtomicInteger namingBugsLeft = new AtomicInteger();

  /** Arms the next {@code count} name reads to fail as a severed connection does. */
  public void loseTheConnection(int count) {
    failuresLeft.set(count);
  }

  /** How many armed failures were never used — zero is the test's proof that the read was hit. */
  public int unspent() {
    return Math.max(0, failuresLeft.get());
  }

  /** The same cutover, on the {@link #nameFor} direction the catalogue reads. */
  public void loseTheConnectionNaming(int count) {
    namingFailuresLeft.set(count);
  }

  /**
   * Arms the next {@code count} {@link #nameFor} reads to fail as a <em>bug</em> does — nothing in
   * the cause chain a cutover would leave. {@code DbRetry} rethrows it on the first attempt, which
   * is what makes it the cheap way to ask what a caller sees when the read simply fails.
   */
  public void failNamingOutright(int count) {
    namingBugsLeft.set(count);
  }

  /** How many of either naming failure were never used. */
  public int unspentNaming() {
    return Math.max(0, namingFailuresLeft.get()) + Math.max(0, namingBugsLeft.get());
  }

  @Override
  public Optional<Repository> findRepositoryByProjectAndName(String projectId, String name) {
    if (failuresLeft.getAndDecrement() > 0) {
      throw cutover();
    }
    return super.findRepositoryByProjectAndName(projectId, name);
  }

  @Override
  public Optional<String> nameFor(Repository repository) {
    if (namingFailuresLeft.getAndDecrement() > 0) {
      throw cutover();
    }
    if (namingBugsLeft.getAndDecrement() > 0) {
      throw new IllegalStateException("the alias read failed");
    }
    return super.nameFor(repository);
  }

  private static JDBCConnectionException cutover() {
    return new JDBCConnectionException(
        "Unable to acquire JDBC Connection",
        new SQLTransientConnectionException(
            "terminating connection due to administrator command", "57P01"));
  }
}
