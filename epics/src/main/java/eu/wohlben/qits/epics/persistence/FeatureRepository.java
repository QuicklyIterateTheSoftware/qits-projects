package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.Feature;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class FeatureRepository implements PanacheRepositoryBase<Feature, String> {

  public List<Feature> listByEpic(String epicId) {
    return find("epicId", epicId).list();
  }

  /** Features whose {@code dependsOnFeatureId} points at {@code featureId}. */
  public List<Feature> listDependents(String featureId) {
    return find("dependsOnFeatureId", featureId).list();
  }
}
