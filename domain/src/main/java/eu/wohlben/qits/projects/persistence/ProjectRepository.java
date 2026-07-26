package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class ProjectRepository implements PanacheRepositoryBase<Project, String> {

  public Optional<Project> findByName(String name) {
    return find("name", name).firstResultOptional();
  }
}
