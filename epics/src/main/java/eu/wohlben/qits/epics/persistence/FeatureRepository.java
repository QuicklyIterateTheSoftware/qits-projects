package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.Feature;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class FeatureRepository implements PanacheRepositoryBase<Feature, String> {

  /** Oldest first — the same order V2's slug backfill ranked duplicates by. */
  public List<Feature> listByEpic(String epicId) {
    return find("epicId", Sort.by("createdAt").and("id"), epicId).list();
  }

  /** Features whose {@code dependsOnFeatureId} points at {@code featureId}. */
  public List<Feature> listDependents(String featureId) {
    return find("dependsOnFeatureId", featureId).list();
  }
}
