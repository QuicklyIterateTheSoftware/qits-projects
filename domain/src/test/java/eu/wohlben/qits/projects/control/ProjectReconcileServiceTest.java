package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ProjectReconciliation.DomainAssertion;
import eu.wohlben.qits.projects.control.ProjectReconciliation.DomainOutcome;
import eu.wohlben.qits.projects.control.ProjectReconciliation.EnvironmentAssertion;
import eu.wohlben.qits.projects.control.ProjectReconciliation.EnvironmentOutcome;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reconcile's decisions that are made <b>before or around</b> a port call, driven directly over
 * the two static seams so the cases a container will not readily produce become plain assertions:
 * no implementation wired at all, an implementation that throws, one that answers nothing, and a
 * project with no stored record.
 *
 * <p>No {@code @QuarkusTest} on purpose — none of this needs a database, a bean or an HTTP server.
 * The wiring is proven where it is visible, in {@code ProjectReconcileControllerTest}; the wire
 * shapes in {@code CdEnvironmentNotifierTest} / {@code DnsDomainRegistrarTest}.
 */
class ProjectReconcileServiceTest {

  private static Project project(ProjectDnsRecord dns) {
    Project project = new Project();
    project.id = "p-1";
    project.name = "Reconciled";
    project.slug = "reconciled";
    project.dns = dns;
    return project;
  }

  private static ProjectDnsRecord record() {
    return new ProjectDnsRecord("app.test.eu", ProjectDnsRecordType.CNAME, "ingress.test.eu");
  }

  /**
   * Absent is a supported configuration for every port here — but a reconcile that asserted nothing
   * must not answer {@code CREATED}. FAILED naming what is missing is the honest outcome, and it is
   * an outcome rather than an exception because the caller asked what happened, not for it to work.
   */
  @Test
  void anUnwiredEnvironmentPortIsAFailedOutcomeAndNotAnException() {
    EnvironmentAssertion assertion =
        ProjectReconcileService.assertEnvironment(List.of(), project(record()));

    assertEquals(EnvironmentOutcome.FAILED, assertion.outcome());
    assertEquals(ProjectReconcileService.NO_NOTIFIER, assertion.detail());
  }

  @Test
  void anUnwiredRegistrarIsAFailedOutcomeToo() {
    DomainAssertion assertion = ProjectReconcileService.assertDomain(List.of(), project(record()));

    assertEquals(DomainOutcome.FAILED, assertion.outcome());
    assertEquals(ProjectReconcileService.NO_REGISTRAR, assertion.detail());
  }

  /**
   * A project with no record is {@code NOT_CONFIGURED}, decided here and <b>without reaching the
   * registrar at all</b> — the absence is this context's own fact (a row predating the columns, a
   * self-seed with no dns configuration), and a registrar handed three nulls could only guess at
   * it.
   */
  @Test
  void aProjectWithNoRecordIsNotConfiguredAndNoRegistrarIsAsked() {
    var registrar = new ThrowingRegistrar();

    DomainAssertion assertion =
        ProjectReconcileService.assertDomain(List.of(registrar), project(null));

    assertEquals(DomainOutcome.NOT_CONFIGURED, assertion.outcome());
    assertNotNull(assertion.detail(), "the answer says why nothing was registered");
    assertTrue(registrar.calls.isEmpty(), "the registrar must not be reached at all");
  }

  /** The stored record is what travels, field for field. */
  @Test
  void theStoredRecordIsWhatTheRegistrarIsHanded() {
    var registrar = new CapturingRegistrar();

    DomainAssertion assertion =
        ProjectReconcileService.assertDomain(List.of(registrar), project(record()));

    assertEquals(DomainOutcome.REGISTERED, assertion.outcome());
    assertNull(assertion.detail());
    assertEquals(
        List.of("p-1|reconciled|app.test.eu|CNAME|ingress.test.eu"),
        registrar.calls,
        "the reconcile re-asserts the stored record and nothing derived");
  }

  /**
   * The port's contract is to answer FAILED rather than throw, so an implementation that throws is
   * a defect — reported as the outcome it should have returned instead of turning a reconcile into
   * a 500. Partial results are the whole point: the other target's answer must survive this one's
   * misbehaviour.
   */
  @Test
  void aThrowingImplementationBecomesTheOutcomeItShouldHaveReturned() {
    EnvironmentAssertion environment =
        ProjectReconcileService.assertEnvironment(
            List.of(new ThrowingNotifier()), project(record()));
    assertEquals(EnvironmentOutcome.FAILED, environment.outcome());
    assertTrue(
        environment.detail().contains("cd exploded"),
        "the cause is what an operator needs to read");

    DomainAssertion domain =
        ProjectReconcileService.assertDomain(List.of(new ThrowingRegistrar()), project(record()));
    assertEquals(DomainOutcome.FAILED, domain.outcome());
    assertTrue(domain.detail().contains("dns exploded"));
  }

  /** A null answer is a misbehaving implementation, not a null outcome for the caller to unpack. */
  @Test
  void anImplementationThatAnswersNothingIsFailed() {
    EnvironmentAssertion environment =
        ProjectReconcileService.assertEnvironment(List.of(new NullNotifier()), project(record()));
    assertEquals(EnvironmentOutcome.FAILED, environment.outcome());
    assertTrue(environment.detail().contains("NullNotifier"));

    DomainAssertion domain =
        ProjectReconcileService.assertDomain(List.of(new NullRegistrar()), project(record()));
    assertEquals(DomainOutcome.FAILED, domain.outcome());
    assertTrue(domain.detail().contains("NullRegistrar"));
  }

  /**
   * A detail is prose for a human, so it is bounded: a receiver's response text must not become
   * this response's transport.
   */
  @Test
  void aDetailIsTruncatedRatherThanCarriedWhole() {
    String detail = EnvironmentAssertion.failed("x".repeat(5_000)).detail();

    assertEquals(ProjectReconciliation.MAX_DETAIL + 1, detail.length(), "truncated and marked");
    assertTrue(detail.endsWith("…"));
    assertNull(EnvironmentAssertion.failed("   ").detail(), "blank prose is no prose");
  }

  private static final class CapturingRegistrar implements ProjectDomainRegistrar {
    final List<String> calls = new ArrayList<>();

    @Override
    public void register(
        String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
      throw new AssertionError("the reconcile must use the synchronous half");
    }

    @Override
    public DomainAssertion registerNow(
        String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
      calls.add(String.join("|", projectId, slug, domain, type.name(), value));
      return DomainAssertion.registered();
    }
  }

  private static final class ThrowingNotifier implements ProjectEnvironmentNotifier {
    @Override
    public void onProjectCreated(String projectId, String name, String slug) {}

    @Override
    public EnvironmentAssertion ensureEnvironment(String projectId, String name, String slug) {
      throw new IllegalStateException("cd exploded");
    }
  }

  private static final class ThrowingRegistrar implements ProjectDomainRegistrar {
    final List<String> calls = new ArrayList<>();

    @Override
    public void register(
        String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {}

    @Override
    public DomainAssertion registerNow(
        String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
      calls.add(projectId);
      throw new IllegalStateException("dns exploded");
    }
  }

  private static final class NullNotifier implements ProjectEnvironmentNotifier {
    @Override
    public void onProjectCreated(String projectId, String name, String slug) {}

    @Override
    public EnvironmentAssertion ensureEnvironment(String projectId, String name, String slug) {
      return null;
    }
  }

  private static final class NullRegistrar implements ProjectDomainRegistrar {
    @Override
    public void register(
        String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {}

    @Override
    public DomainAssertion registerNow(
        String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
      return null;
    }
  }
}
