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
 * <p><b>{@code @Alternative @Priority} is kept as the rule for this shape of fake.</b> It was
 * load-bearing while {@code service}'s suite also had the shipped registrar as a bean: two
 * implementations, and {@code ProjectReconcileService} — which has one answer to give and so takes
 * the first candidate — would report whichever the container happened to hand it. Nothing under
 * {@code src/main} implements the port now, so there is nothing left to displace; the annotations
 * stay because a fake for a port whose method returns a result must carry them, and the next
 * implementation must not silently start competing with this one.
 *
 * <p>The seam, not the wire. How a name resolves to a zone and how a receiver's status code becomes
 * an outcome belong to whatever implements the port; what this proves is that a created project asks
 * at all, with the record it was created with, that one without a record asks nothing, and that a
 * reconcile asks again. Nothing in {@code src/main} references this class.
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
   * Forgets everything recorded so far and restores the default answer. {@code @QuarkusTest} shares
   * one application — and therefore one instance of this bean — across a class, so a test that
   * counts registrations or scripts an outcome has to start from a known state.
   */
  public synchronized void clear() {
    registrations.clear();
    reassertions.clear();
    scripted = DomainAssertion.registered();
  }
}
