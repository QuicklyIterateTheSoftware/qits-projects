package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.persistence.RepositoryNameRepository;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
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
   */
  public Optional<ProjectScopedName> resolve(String repoId) {
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
        last =
            e; // most likely a concurrent registerSelfName hitting UK_repository_name_project_name
      }
    }
    throw last;
  }
}
