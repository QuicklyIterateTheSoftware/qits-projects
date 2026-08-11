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
 * <p><b>{@code @Alternative} with no {@code @Priority}</b>: it is enabled by one test profile
 * (`RepositoryNameCutoverTest.OneLostConnection`) and is inert in every other class of this suite. A
 * globally enabled one would sit in the path of every repository read here, armed or not.
 */
@Alternative
@ApplicationScoped
public class ConnectionLosingRepositoryNames extends RepositoryNameRepository {

  private final AtomicInteger failuresLeft = new AtomicInteger();

  /** Arms the next {@code count} name reads to fail as a severed connection does. */
  public void loseTheConnection(int count) {
    failuresLeft.set(count);
  }

  /** How many armed failures were never used — zero is the test's proof that the read was hit. */
  public int unspent() {
    return Math.max(0, failuresLeft.get());
  }

  @Override
  public Optional<Repository> findRepositoryByProjectAndName(String projectId, String name) {
    if (failuresLeft.getAndDecrement() > 0) {
      throw new JDBCConnectionException(
          "Unable to acquire JDBC Connection",
          new SQLTransientConnectionException(
              "terminating connection due to administrator command", "57P01"));
    }
    return super.findRepositoryByProjectAndName(projectId, name);
  }
}
