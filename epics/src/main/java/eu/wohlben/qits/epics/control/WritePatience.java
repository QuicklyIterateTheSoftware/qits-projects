package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.db.DbRetry;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one place the epics write seams open their transaction, so a postgres cutover costs a pause
 * rather than a refused edit.
 *
 * <p><b>Why a write may be retried at all.</b> {@link ReadPatience} exists because re-running a read
 * is free; a write is not, and the usual answer is to leave it alone. {@link DbRetry#inNewTx} is the
 * narrower offer: it owns the transaction boundary, so it can tell an attempt that <em>certainly</em>
 * did not commit — the body threw a connection-class failure, which Quarkus rolls back and never
 * commits — from one whose outcome nobody can place, which is anything the transaction manager
 * itself reports. Only the first is retried. A lost commit acknowledgement is still an error the
 * caller sees, and that residue is the design rather than a gap in it.
 *
 * <p><b>THE ONE RULE the bodies obey: they are database-only.</b> The retry re-runs the whole body,
 * so anything in it that is not a row — an SSE hint, an HTTP call, a git push — would happen twice.
 * That is why the wrap sits here in {@code epics} and not around the callers: {@code EpicController}
 * and {@code EpicMcpTools} both fire an SSE change hint <em>after</em> the service call returns, and
 * neither notification is inside the retry.
 *
 * <p><b>The flush is not optional.</b> Hibernate flushes at commit by default, which would put every
 * INSERT on the far side of the line {@code inNewTx} can classify — the whole write would land in
 * the undecidable commit phase and never be retried. Flushing as the last thing in the body moves it
 * into the statement phase, where a severed connection is a certain no-commit. One line, and it is
 * the difference between this bean helping and this bean reporting.
 *
 * <p><b>Replaces {@code @Transactional} on the seams it wraps</b>, rather than sitting outside it:
 * {@code inNewTx} runs {@code QuarkusTransaction.requiringNew()} per attempt, and a method that
 * still declared {@code @Transactional} would have joined a transaction that the retry could not
 * replace. {@code AuditService.record} keeps its {@code @Transactional} and joins this one, exactly
 * as it joined the annotation's.
 *
 * <p>A bean rather than a constant for the same two reasons as {@link ReadPatience}: the deadline is
 * configurable, and a suite can shorten it — a give-up test at the shipped fifteen seconds is a
 * fifteen-second test.
 */
@ApplicationScoped
public class WritePatience {

  /**
   * How long an epics write holds before the outage is a failure worth reporting. The shipped value
   * matches {@code DbRetry.DEFAULT_DEADLINE}, {@link ReadPatience}'s and the datasource's {@code
   * acquisition-timeout}.
   */
  @ConfigProperty(name = "qits.epics.write-deadline", defaultValue = "15S")
  Duration deadline;

  /** The epics persistence unit, for the flush that puts the write in the statement phase. */
  @Inject
  @PersistenceUnit("epics")
  EntityManager entityManager;

  /**
   * Runs {@code write} in a transaction of its own, retrying an attempt that certainly did not
   * commit. {@code what} names the work in the log — it is read by a person after an outage, so name
   * the change rather than the method.
   */
  public <T> T hold(String what, Callable<T> write) {
    return DbRetry.inNewTx(
        what,
        () -> {
          T answer = write.call();
          entityManager.flush();
          return answer;
        },
        deadline);
  }

  /**
   * The {@link Runnable} shape of {@link #hold}, under its own name for the reason {@code
   * DbRetry.runInNewTx} carries: {@code () -> repository.delete(id)} fits both functional interfaces
   * at once, and two same-named methods would make that call site ambiguous rather than convenient.
   */
  public void run(String what, Runnable write) {
    hold(
        what,
        () -> {
          write.run();
          return null;
        });
  }
}
