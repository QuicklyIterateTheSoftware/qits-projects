package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.Task;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class TaskRepository implements PanacheRepositoryBase<Task, String> {

  public List<Task> listByFeature(String featureId) {
    return find("featureId", featureId).list();
  }

  /** Tasks whose {@code dependsOnTaskId} points at {@code taskId}. */
  public List<Task> listDependents(String taskId) {
    return find("dependsOnTaskId", taskId).list();
  }
}
