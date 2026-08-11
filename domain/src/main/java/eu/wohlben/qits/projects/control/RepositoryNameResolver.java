package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Resolves a repository's <b>project-scoped name</b> — the {@code (projectId, name)} pair that
 * addresses it as a served sibling under the git host's {@code /git/<projectId>/<name>} route,
 * registering a self-name on first miss so the value is stable thereafter. Shared by the two places
 * that need it: {@link WorkspaceService#cloneUrl} (composes the clone/push url and the submodule
 * override url) and {@link WorkspaceContainerFactory} (injects it as {@code
 * QITS_WORKSPACE_DAEMON_PROJECT_ID}/{@code …_REPO_NAME} so the in-container workspace-daemon can
 * self-clone name-addressed, letting committed relative submodule urls resolve natively — see
 * docs/epics/qits-workspace-daemon/ Part 1).
 *
 * <p>Runs in its own transaction (both callers run off any request context — the provision worker
 * thread, or container creation) and retries the unique-constraint race: two workspaces of the same
 * still-alias-less repository can concurrently {@code registerSelfName}, and the retry lets the
 * loser read the winner's just-committed alias.
 */
@ApplicationScoped
public class RepositoryNameResolver {

  @Inject RepositoryRepository repositoryRepository;

  @Inject RepositoryNameRepository repositoryNameRepository;

  /** A repository's project-scoped git-host address. */
  public record ProjectScopedName(String projectId, String name) {}

  /**
   * The {@code (projectId, name)} for {@code repoId}, ensuring a self-name exists, or {@link
   * Optional#empty()} when the repository or its project is absent — the caller then id-addresses
   * ({@code /git/<repoId>}), exactly as before name-addressing existed.
   *
   * <p>Two retries, and they are not the same retry. The inner loop is the unique-constraint race
   * and nothing else; {@link DbRetry} outside it is the postgres cutover. Both are needed and
   * neither can do the other's job — the race resolves on the very next attempt because the winner
   * has already committed, while a database that is gone needs a pause and a deadline.
   */
  public Optional<ProjectScopedName> resolve(String repoId) {
    // OUTSIDE requiringNew, which is the placement rule: each retry opens its own transaction, and
    // a retry inside one would re-run statements on a connection already marked rollback-only.
    return DbRetry.call("repository self-name resolution", () -> resolveThroughTheRace(repoId));
  }

  /**
   * The self-name, retrying the unique-constraint race: two workspaces of the same still-alias-less
   * repository can concurrently {@code registerSelfName}, and the retry lets the loser read the
   * winner's just-committed alias.
   *
   * <p><b>A lost connection is not that race and is not absorbed here.</b> This loop used to swallow
   * every {@code RuntimeException} for three attempts and then rethrow the last, which spent all
   * three on a database that was not there and reported a constraint race that never happened.
   * Connection-class failures are rethrown on sight so the caller's {@link DbRetry} — which pauses
   * and has a deadline — is what handles them.
   */
  private Optional<ProjectScopedName> resolveThroughTheRace(String repoId) {
    RuntimeException last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo = repositoryRepository.findById(repoId);
                  if (repo == null || repo.project == null) {
                    return Optional.<ProjectScopedName>empty();
                  }
                  String name =
                      repositoryNameRepository
                          .nameFor(repo)
                          .orElseGet(() -> repositoryNameRepository.registerSelfName(repo));
                  return Optional.of(new ProjectScopedName(repo.project.id, name));
                });
      } catch (RuntimeException e) {
        if (DbRetry.isConnectionFailure(e)) {
          throw e;
        }
        last =
            e; // most likely a concurrent registerSelfName hitting UK_repository_name_project_name
      }
    }
    throw last;
  }
}
