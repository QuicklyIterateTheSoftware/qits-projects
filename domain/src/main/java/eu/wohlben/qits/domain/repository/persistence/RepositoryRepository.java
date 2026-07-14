package eu.wohlben.qits.domain.repository.persistence;

import eu.wohlben.qits.domain.repository.entity.Repository;
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
}
