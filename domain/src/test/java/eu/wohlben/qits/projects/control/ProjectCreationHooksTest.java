package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.testsupport.RecordingProjectDomainRegistrar;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The port {@code ProjectService.create} fires (main-environment-plan.md §2), at the seam rather
 * than on the wire: <b>did this context ask</b>, with which values, on which paths.
 *
 * <p>Both assertions the wire cannot make live here — that the hook hangs off the <em>service</em>
 * and so fires for every creation path including the ones no HTTP request reaches, and that a
 * project with no record asks the registrar nothing. What actually leaves the process is {@code
 * DnsDomainRegistrarTest}'s business.
 */
@QuarkusTest
public class ProjectCreationHooksTest {

  @Inject ProjectService projectService;
  @Inject RecordingProjectDomainRegistrar domains;

  /** One application, and therefore one recording bean, is shared across the class. */
  @BeforeEach
  void clearRecordings() {
    domains.clear();
  }

  @Test
  public void aCreatedProjectRegistersItsDomain() {
    ProjectDnsRecord record =
        new ProjectDnsRecord("hooks.test.eu", ProjectDnsRecordType.A, "203.0.113.9");

    Project project = projectService.create("Hooks", "hooks", "desc", null, record);

    var registration = domains.registrationFor(project.id).orElseThrow();
    assertEquals("hooks", registration.slug());
    assertEquals("hooks.test.eu", registration.domain());
    assertEquals(ProjectDnsRecordType.A, registration.type());
    assertEquals("203.0.113.9", registration.value());
  }

  /**
   * No record ⇒ the registrar is not called at all. Registering nothing is the documented state of a
   * project without a domain, not a failure to configure one.
   */
  @Test
  public void aProjectWithoutARecordRegistersNoDomain() {
    Project project = projectService.create("No Domain", "no-domain", null);

    assertTrue(domains.registrationFor(project.id).isEmpty());
    assertNull(projectService.get(project.id).dns);
  }

  /**
   * Every overload lands on the same path, so every overload registers — which is what makes the
   * self-seed and any future caller reach the port without knowing it exists. The overloads that
   * take no record register nothing, so the one with a record is what the count has to see.
   */
  @Test
  public void everyCreateOverloadRunsTheHook() {
    projectService.create("Two Arg", "two-arg-desc");
    projectService.create("Three Arg", "three-arg", null);
    projectService.create("Four Arg", "four-arg", null, null);
    Project five =
        projectService.create(
            "Five Arg",
            "five-arg",
            null,
            null,
            new ProjectDnsRecord("five-arg.test.eu", ProjectDnsRecordType.A, "203.0.113.9"));

    assertTrue(domains.registrationFor(five.id).isPresent());
    assertEquals(1, domains.registrations().size(), "once per created project with a record");
  }

  /**
   * The row is committed before the ports are called, so an implementation that reads the project
   * back finds it. Asserted the only way a test can see it: read the row in a <b>fresh</b>
   * transaction from inside the port, where an L1-cached or still-uncommitted row would not be
   * visible.
   */
  @Test
  public void theHooksRunAfterTheCreatingTransactionCommits() {
    Project project =
        projectService.create(
            "Committed",
            "committed",
            null,
            null,
            new ProjectDnsRecord(
                "committed.test.eu", ProjectDnsRecordType.CNAME, "ingress.test.eu"));

    String projectId = domains.registrationFor(project.id).orElseThrow().projectId();
    Project readBack = QuarkusTransaction.requiringNew().call(() -> projectService.get(projectId));
    assertNotNull(readBack, "the registered project must be readable when the port is called");
    assertEquals("committed.test.eu", readBack.dns.domain);
  }

  /**
   * A half-filled record is refused in the DOMAIN layer, not only at the API: the self-seed reads
   * three config keys and reaches {@code create} without Bean Validation, and a record with some
   * columns set and others null would make the null-embeddable read that "no domain" depends on
   * stop meaning one thing.
   */
  @Test
  public void createRefusesAHalfFilledOrMalformedRecordWithoutCreatingAnything() {
    assertThrows(
        BadRequestException.class,
        () ->
            projectService.create(
                "Bad",
                "bad-domain",
                null,
                null,
                new ProjectDnsRecord(null, ProjectDnsRecordType.A, "203.0.113.9")));
    assertThrows(
        BadRequestException.class,
        () ->
            projectService.create(
                "Bad",
                "bad-type",
                null,
                null,
                new ProjectDnsRecord("bad.test.eu", null, "203.0.113.9")));
    assertThrows(
        BadRequestException.class,
        () ->
            projectService.create(
                "Bad",
                "bad-value",
                null,
                null,
                new ProjectDnsRecord("bad.test.eu", ProjectDnsRecordType.CNAME, "  ")));
    assertThrows(
        BadRequestException.class,
        () ->
            projectService.create(
                "Bad",
                "bad-upper",
                null,
                null,
                new ProjectDnsRecord("UPPER.CASE.EU", ProjectDnsRecordType.A, "203.0.113.9")));

    assertTrue(
        domains.registrations().isEmpty(),
        "a rejected creation registers nothing — validation precedes the transaction");
  }
}
