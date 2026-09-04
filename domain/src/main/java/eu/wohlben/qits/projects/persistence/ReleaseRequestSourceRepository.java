package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The named sources of release requests. Plain CRUD like its parent's repository — {@code
 * ReleaseRequests} owns every transaction, because the writers are a request thread, the bus
 * consumption and the sweep, and each brackets itself.
 */
@ApplicationScoped
public class ReleaseRequestSourceRepository
    implements PanacheRepositoryBase<ReleaseRequestSource, String> {

  /** One request's named sources, in the order they were put on it — {@code main} first. */
  public List<ReleaseRequestSource> listByRequest(String requestId) {
    return list("requestId = ?1 order by addedAt, name", requestId);
  }

  /** Every named source of a set of requests, for a list read that must not query per row. */
  public List<ReleaseRequestSource> listByRequests(List<String> requestIds) {
    if (requestIds.isEmpty()) {
      return List.of();
    }
    return list("requestId in ?1 order by addedAt, name", requestIds);
  }

  /** The row naming this branch on this request, if it is already a source — the add's idempotency. */
  public Optional<ReleaseRequestSource> find(
      String requestId, ReleaseRequestSource.Kind kind, String name) {
    return find("requestId = ?1 and kind = ?2 and name = ?3", requestId, kind, name)
        .firstResultOptional();
  }
}
