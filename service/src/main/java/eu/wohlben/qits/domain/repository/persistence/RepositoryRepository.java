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
}
