package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.ProjectDomainRegistrar;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A TEST-SCOPE implementation of the {@link ProjectDomainRegistrar} port that records the
 * registration instead of writing to a nameserver.
 *
 * <p>The seam, not the wire — the counterpart of {@link RecordingProjectEnvironmentNotifier}. Zone
 * resolution, the {@code @}-versus-label spelling and the token header are qits-dns' contract and
 * are pinned by {@code DnsDomainRegistrarTest}; what this proves is that a created project asks at
 * all, with the record it was created with, and that one without a record asks nothing. Nothing in
 * {@code src/main} references this class.
 */
@ApplicationScoped
public class RecordingProjectDomainRegistrar implements ProjectDomainRegistrar {

  /** One registration, exactly as the port delivered it. */
  public record Registration(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {}

  private final List<Registration> registrations = new ArrayList<>();

  @Override
  public synchronized void register(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
    registrations.add(new Registration(projectId, slug, domain, type, value));
  }

  public synchronized List<Registration> registrations() {
    return List.copyOf(registrations);
  }

  public synchronized Optional<Registration> registrationFor(String projectId) {
    return registrations.stream().filter(r -> r.projectId().equals(projectId)).findFirst();
  }

  /**
   * Forgets everything recorded so far — see {@link RecordingProjectEnvironmentNotifier#clear()}.
   */
  public synchronized void clear() {
    registrations.clear();
  }
}
