package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class RepositoryRepository implements PanacheRepositoryBase<Repository, String> {

  public Optional<Repository> findByUrl(String url) {
    return find("url", url).firstResultOptional();
  }

  /**
   * A repository with {@code url} within a single project — the dedup key for recursive submodule
   * import. Scoped to the project because a {@code Repository} belongs to exactly one project, so
   * the same submodule url in two different projects yields two independent mirrors ("dedup within
   * a project, isolate across projects").
   */
  public Optional<Repository> findByUrlInProject(String url, String projectId) {
    return find("url = ?1 and project.id = ?2", url, projectId).firstResultOptional();
  }

  /**
   * The project's wrapper repository — its {@link RepositoryArchetype#PROJECT} root superproject —
   * if it has one.
   *
   * <p>"At most one wrapper per project" is carried by this query plus the guard in {@code
   * ProjectService.adoptWrapperRepository}, deliberately <em>not</em> by a {@code
   * project.wrapper_repository_id} FK: the FK would enforce it structurally but duplicates what
   * {@code archetype} already says. Projects created before the wrapper feature have none.
   */
  public Optional<Repository> findWrapperByProject(String projectId) {
    return find("project.id = ?1 and archetype = ?2", projectId, RepositoryArchetype.PROJECT)
        .firstResultOptional();
  }
}
