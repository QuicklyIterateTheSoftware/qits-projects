package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.persistence.EpicRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.JDBCConnectionException;

/**
 * The epic table with a postgres cutover in it: the next {@code listByProject} reads throw what a
 * caller actually sees when its connection dies mid-flight, then the table answers normally again.
 *
 * <p>The failure is the real shape rather than a marker — Hibernate's {@code
 * JDBCConnectionException} wrapping postgres' {@code 57P01} ("terminating connection due to
 * administrator command"), which is what the server says to every open connection while it is being
 * replaced. {@code DbRetry} decides what to retry by walking that cause chain, so a stand-in
 * exception would prove the retry runs and not that it fires on a cutover. The same stand-in
 * `qits-projects`' `service` module uses for the by-name read.
 *
 * <p><b>{@code @Alternative} with no {@code @Priority}</b>: one test profile enables it and it is
 * inert everywhere else in this suite. A globally enabled one would sit in the path of every epic
 * list here — including the one {@code EpicService.insert} makes to mint a slug.
 */
@Alternative
@ApplicationScoped
public class ConnectionLosingEpics extends EpicRepository {

  private final AtomicInteger failuresLeft = new AtomicInteger();

  /** Arms the next {@code count} project listings to fail as a severed connection does. */
  public void loseTheConnection(int count) {
    failuresLeft.set(count);
  }

  /** How many armed failures were never used — zero is the test's proof that the read was hit. */
  public int unspent() {
    return Math.max(0, failuresLeft.get());
  }

  @Override
  public List<Epic> listByProject(String projectId) {
    if (failuresLeft.getAndDecrement() > 0) {
      throw new JDBCConnectionException(
          "Unable to acquire JDBC Connection",
          new SQLTransientConnectionException(
              "terminating connection due to administrator command", "57P01"));
    }
    return super.listByProject(projectId);
  }
}
