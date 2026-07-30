package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.ProjectDomainRegistrar;
import eu.wohlben.qits.projects.control.ProjectReconciliation.DomainAssertion;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A TEST-SCOPE implementation of the {@link ProjectDomainRegistrar} port that records the
 * registration instead of writing to a nameserver, and answers the synchronous half with whatever
 * the test scripted.
 *
 * <p>The seam, not the wire — the counterpart of {@link RecordingProjectEnvironmentNotifier}, whose
 * javadoc explains why both are {@code @Alternative @Priority}. Zone resolution, the
 * apex-versus-label spelling, the token header and how a status code becomes an outcome are
 * qits-dns' contract and are pinned by {@code DnsDomainRegistrarTest}; what this proves is that a
 * created project asks at all, with the record it was created with, that one without a record asks
 * nothing, and that a reconcile asks again. Nothing in {@code src/main} references this class.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingProjectDomainRegistrar implements ProjectDomainRegistrar {

  /** One registration, exactly as the port delivered it. */
  public record Registration(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {}

  private final List<Registration> registrations = new ArrayList<>();

  private final List<Registration> reassertions = new ArrayList<>();

  private DomainAssertion scripted = DomainAssertion.registered();

  @Override
  public synchronized void register(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
    registrations.add(new Registration(projectId, slug, domain, type, value));
  }

  @Override
  public synchronized DomainAssertion registerNow(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
    reassertions.add(new Registration(projectId, slug, domain, type, value));
    return scripted;
  }

  /** What the next {@link #registerNow} answers. */
  public synchronized void willAnswer(DomainAssertion assertion) {
    this.scripted = assertion;
  }

  public synchronized List<Registration> registrations() {
    return List.copyOf(registrations);
  }

  public synchronized Optional<Registration> registrationFor(String projectId) {
    return registrations.stream().filter(r -> r.projectId().equals(projectId)).findFirst();
  }

  /** The synchronous re-assertions a reconcile asked for, in order. */
  public synchronized List<Registration> reassertions() {
    return List.copyOf(reassertions);
  }

  /**
   * Forgets everything recorded so far and restores the default answer — see {@link
   * RecordingProjectEnvironmentNotifier#clear()}.
   */
  public synchronized void clear() {
    registrations.clear();
    reassertions.clear();
    scripted = DomainAssertion.registered();
  }
}
