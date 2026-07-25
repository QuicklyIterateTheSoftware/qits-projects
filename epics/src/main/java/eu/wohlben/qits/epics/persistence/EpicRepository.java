package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.Epic;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class EpicRepository implements PanacheRepositoryBase<Epic, String> {

  public List<Epic> listByProject(String projectId) {
    return find("projectId", projectId).list();
  }
}
