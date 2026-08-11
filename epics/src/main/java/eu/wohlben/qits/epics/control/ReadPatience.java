package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one place the epics read seams say how long they hold a request through a postgres cutover.
 *
 * <p><b>What it is for.</b> Replacing the tier's postgres severs every pooled connection. A list
 * read caught mid-flight fails with a connection-class exception, and the SPA renders the empty
 * board that comes back as "this epic has no features" — an answer, not an outage. {@link DbRetry}
 * retries connection-class failures only; everything else is rethrown on the first attempt, so a
 * missing row is still an immediate 404.
 *
 * <p><b>Where a wrap may go.</b> Reads only, and only OUTSIDE any transaction: the retried block
 * re-runs, and re-running statements on a connection already marked rollback-only fails a second
 * time for a reason nobody wrote down. That rules out the list reads inside this module's {@code
 * @Transactional} writes — slug uniqueness, cascade deletes, the dependents cleared on delete —
 * which reach the repositories directly and are deliberately not routed through here. See
 * db-patience-plan.md.
 *
 * <p>A bean rather than a constant so the deadline is configurable, and so a suite can shorten it:
 * a give-up test at the shipped 15 seconds is a 15-second test.
 */
@ApplicationScoped
public class ReadPatience {

  /**
   * How long an epics read holds before the outage is a failure worth reporting. The shipped value
   * matches {@code DbRetry.DEFAULT_DEADLINE} and the datasource's {@code acquisition-timeout}.
   */
  @ConfigProperty(name = "qits.epics.read-deadline", defaultValue = "15S")
  Duration deadline;

  /**
   * Runs {@code read} with that patience. {@code what} names the work in the log — it is read by a
   * person after an outage, so name the question rather than the method.
   */
  public <T> T hold(String what, Supplier<T> read) {
    return DbRetry.call(what, read, deadline);
  }
}
