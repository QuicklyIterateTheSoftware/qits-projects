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
import eu.wohlben.qits.projects.testsupport.RecordingProjectEnvironmentNotifier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two ports {@code ProjectService.create} fires (main-environment-plan.md §1–2), at the seam
 * rather than on the wire: <b>did this context ask</b>, with which values, on which paths.
 *
 * <p>Both assertions the wire cannot make live here — that the hooks hang off the <em>service</em>
 * and so fire for every creation path including the ones no HTTP request reaches, and that a
 * project with no record asks the registrar nothing. What actually leaves the process is {@code
 * CdEnvironmentNotifierTest} and {@code DnsDomainRegistrarTest}'s business.
 */
@QuarkusTest
public class ProjectCreationHooksTest {

  @Inject ProjectService projectService;
  @Inject RecordingProjectEnvironmentNotifier environments;
  @Inject RecordingProjectDomainRegistrar domains;

  /** One application, and therefore one of each recording bean, is shared across the class. */
  @BeforeEach
  void clearRecordings() {
    environments.clear();
    domains.clear();
  }

  @Test
  public void aCreatedProjectAnnouncesItsEnvironmentAndRegistersItsDomain() {
    ProjectDnsRecord record =
        new ProjectDnsRecord("hooks.test.eu", ProjectDnsRecordType.A, "203.0.113.9");

    Project project = projectService.create("Hooks", "hooks", "desc", null, record);

    var announcement = environments.announcementFor(project.id).orElseThrow();
    assertEquals("Hooks", announcement.name());
    assertEquals("hooks", announcement.slug(), "the slug is what a receiver keys on, not the name");

    var registration = domains.registrationFor(project.id).orElseThrow();
    assertEquals("hooks", registration.slug());
    assertEquals("hooks.test.eu", registration.domain());
    assertEquals(ProjectDnsRecordType.A, registration.type());
    assertEquals("203.0.113.9", registration.value());
  }

  /**
   * No record ⇒ the environment is still announced and the registrar is not called at all.
   * Registering nothing is the documented state of a project without a domain, not a failure to
   * configure one.
   */
  @Test
  public void aProjectWithoutARecordAnnouncesItsEnvironmentAndRegistersNoDomain() {
    Project project = projectService.create("No Domain", "no-domain", null);

    assertTrue(environments.announcementFor(project.id).isPresent());
    assertTrue(domains.registrationFor(project.id).isEmpty());
    assertNull(projectService.get(project.id).dns);
  }

  /**
   * Every overload lands on the same path, so every overload announces — which is what makes the
   * self-seed and any future caller get an environment without knowing the port exists.
   */
  @Test
  public void everyCreateOverloadAnnounces() {
    Project two = projectService.create("Two Arg", "two-arg-desc");
    Project three = projectService.create("Three Arg", "three-arg", null);
    Project four = projectService.create("Four Arg", "four-arg", null, null);

    assertTrue(environments.announcementFor(two.id).isPresent());
    assertTrue(environments.announcementFor(three.id).isPresent());
    assertTrue(environments.announcementFor(four.id).isPresent());
    assertEquals(3, environments.announcements().size(), "once per created project, no more");
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

    String projectId = environments.announcementFor(project.id).orElseThrow().projectId();
    Project readBack = QuarkusTransaction.requiringNew().call(() -> projectService.get(projectId));
    assertNotNull(readBack, "the announced project must be readable when the port is called");
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
        environments.announcements().isEmpty(),
        "a rejected creation announces nothing — validation precedes the transaction");
    assertTrue(domains.registrations().isEmpty());
  }
}
