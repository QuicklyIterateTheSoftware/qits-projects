package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.entity.CommitBuildStatus;
import eu.wohlben.qits.projects.persistence.CommitBuildStatusRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The per-commit build-status ledger: what qits-ci has said about each commit, recorded from the
 * bus and answered to the repositories API.
 *
 * <p>This is the foundation half of the release-quality-gates work: a release request's build gate
 * will read {@link #verdictsOf} — and later resolve pending gates in the same transaction {@link
 * #record} writes — which is the reason the ledger lives in this service at all, beside the
 * repository aggregate and the request state machine, rather than in the git host.
 *
 * <p><b>{@code record} opens its own transaction</b>, because its caller is a durable bus listener
 * running under the claim transaction — which lives on the <em>eventstream</em> datasource, and one
 * JTA transaction does not take two non-XA datasources (qits-ci measured it as {@code Enlisted
 * connection used without active transaction}; qits-deployments' subscriber makes the same
 * arrangement). The direction that can go wrong is a claim that commits after this write rolled
 * back — impossible, since a throw here propagates and rolls the claim back too — and a write that
 * commits under a claim that then rolls back, which the run-id upsert makes convergent: the
 * redelivered event writes the same row again.
 */
@ApplicationScoped
public class BuildStatusLedger {

  @Inject CommitBuildStatusRepository statuses;

  /** One run's terminal verdict, as the listener hands it over — plain values, no wire types. */
  public record Verdict(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      String status,
      boolean gating,
      Instant finishedAt,
      UUID causationId) {}

  /** Record one verdict, in a transaction of this datasource's own. Idempotent per run id. */
  public void record(Verdict verdict) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CommitBuildStatus row = new CommitBuildStatus();
              row.runId = verdict.runId();
              row.repoId = verdict.repoId();
              row.projectId = verdict.projectId();
              row.repoName = verdict.repoName();
              row.branch = verdict.branch();
              row.commitSha = verdict.commitSha();
              row.status = verdict.status();
              row.gating = verdict.gating();
              row.finishedAt = verdict.finishedAt();
              row.causationId = verdict.causationId();
              statuses.put(row);
            });
  }

  /** Every verdict for one commit, newest run first. Empty means "no verdict yet", not "no run". */
  public List<CommitBuildStatusDto> verdictsOf(String repoId, String commitSha) {
    return statuses.findByCommit(repoId, commitSha).stream()
        .map(
            row ->
                new CommitBuildStatusDto(
                    row.runId, row.status, row.branch, row.gating, row.finishedAt))
        .toList();
  }
}
