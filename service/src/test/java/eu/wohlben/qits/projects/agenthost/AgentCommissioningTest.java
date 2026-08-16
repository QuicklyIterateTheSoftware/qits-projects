package eu.wohlben.qits.projects.agenthost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The credential a project's agent container is started with: commissioned for a fresh container,
 * carried unchanged into a wake, and never minted twice for one container.
 *
 * <p><b>Asserted at the factory, because that is where the two arms differ.</b> The ladder's own
 * seam is {@link FakeContainerRuntime}, which builds no spec at all — so a test driven through
 * {@code POST …/ensure} would prove the ladder and say nothing about what a container is handed. The
 * one thing that does belong at the ladder is the last test here: that a commissioning failure
 * arrives as the kind of exception {@code AgentContainers.ensure} turns into {@code FAILED} rather
 * than the kind it rethrows with a status.
 */
@QuarkusTest
class AgentCommissioningTest {

  private static final String SLUG = "demo";
  private static final String WRAPPER = "demo-demo";

  @Inject AgentContainerFactory factory;

  @Inject AgentCommissions commissions;

  @Inject FakeAgentCredentials credentials;

  @Inject FakeContainerRuntime runtime;

  private String projectId;

  @BeforeEach
  void setUp() {
    credentials.reset();
    runtime.reset();
    // A fresh id per test, so no row one test wrote is in another's way.
    projectId = UUID.randomUUID().toString();
  }

  /**
   * The rows go too. {@code agent_credential} is real state in a database Flyway only cleans between
   * application starts, and {@code AgentCredentialReconcileTest} reads the whole table.
   */
  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    commissions.handBack(projectId);
    credentials.reset();
  }

  private Map<String, String> fresh() {
    return factory.forProject(projectId, SLUG, WRAPPER).spec().env();
  }

  private Map<String, String> wake() {
    return factory.forRestart(projectId, SLUG, WRAPPER).spec().env();
  }

  @Test
  void aFreshContainerIsCommissionedAndCarriesThePair() {
    credentials.enable();

    Map<String, String> env = fresh();

    assertEquals(List.of("commission:" + projectId), credentials.calls());
    Map<String, String> live = credentials.live();
    assertEquals(1, live.size(), "one commission for one container");
    String clientId = live.keySet().iterator().next();
    assertEquals(projectId, live.get(clientId), "commissioned for THIS project's context");
    assertEquals(clientId, env.get("QITS_COMMISSIONED_CLIENT_ID"));
    assertEquals("secret-of-" + clientId, env.get("QITS_COMMISSIONED_CLIENT_SECRET"));
    assertEquals(
        "http://qits-idp:8080/idp/token", env.get("QITS_PROJECTS_DAEMON_AUTH_TOKEN_URL"));
    assertEquals("qits-projects", env.get("QITS_PROJECTS_DAEMON_AUTH_AUDIENCE"));
  }

  /**
   * The credential belongs to the container, so waking one sends the pair it was created with and
   * asks the idp for nothing.
   *
   * <p><b>The whole environment is compared, not just the two names.</b> qits-containers hashes a
   * workload's spec, environment included, to decide whether an ensure starts the container in place
   * or replaces it — so a wake whose environment differed by one byte would recreate the container
   * on every wake, which is the defect {@code ContainerRuntime.restart} records. This assertion is
   * what notices if a fresh pair is ever minted here.
   */
  @Test
  void wakingAContainerSendsTheSamePairAndCommissionsNothing() {
    credentials.enable();
    Map<String, String> created = fresh();

    Map<String, String> woken = wake();

    assertEquals(
        List.of("commission:" + projectId),
        credentials.calls(),
        "one commission for the container's whole life");
    assertEquals(created, woken, "byte for byte the environment the container was created with");
  }

  /**
   * A container being replaced is a new context, so the credential the old one held is handed back
   * before the new one is minted. Leaving it live would leak a credential nothing can present.
   */
  @Test
  void aReplacementContainerHandsTheOldCredentialBackFirst() {
    credentials.enable();
    Map<String, String> first = fresh();
    String firstClientId = first.get("QITS_COMMISSIONED_CLIENT_ID");

    Map<String, String> second = fresh();

    assertEquals(
        List.of(
            "commission:" + projectId,
            "decommission:" + firstClientId,
            "commission:" + projectId),
        credentials.calls());
    assertEquals(
        Map.of(second.get("QITS_COMMISSIONED_CLIENT_ID"), projectId),
        credentials.live(),
        "exactly one live credential per container");
  }

  /**
   * The shipped configuration. No idp means no credential to commission with, and the spec a
   * container is started with must be the spec it was before any of this existed — otherwise "no
   * idp" is a degraded mode rather than a supported one.
   */
  @Test
  void withNoIdpNothingIsCommissionedAndTheSpecIsUnchanged() {
    Map<String, String> created = fresh();
    Map<String, String> woken = wake();

    assertEquals(List.of(), credentials.calls(), "not one call is made");
    assertNull(created.get("QITS_COMMISSIONED_CLIENT_ID"));
    assertNull(created.get("QITS_COMMISSIONED_CLIENT_SECRET"));
    assertNull(created.get("QITS_PROJECTS_DAEMON_AUTH_TOKEN_URL"));
    assertNull(created.get("QITS_PROJECTS_DAEMON_AUTH_AUDIENCE"));
    assertTrue(
        created.keySet().stream().noneMatch(name -> name.startsWith("QITS_COMMISSIONED")),
        "no commissioning name is in the map at all: " + created.keySet());
    assertEquals(created, woken);
  }

  /**
   * An answer about the moment — an idp mid-cutover — is asked again inside the window, which is the
   * only way a credential is ever commissioned across one.
   */
  @Test
  void anAnswerAboutTheMomentIsAskedAgain() {
    credentials.enable();
    credentials.failCommissions(1, new AgentCredentialException("qits-idp unreachable", true));

    Map<String, String> env = fresh();

    assertEquals(
        List.of("commission:" + projectId, "commission:" + projectId), credentials.calls());
    assertTrue(env.containsKey("QITS_COMMISSIONED_CLIENT_ID"));
  }

  /**
   * An answer about the request is one attempt and one failure. A container that should hold a
   * credential and does not has every read refused later, a long way from here, so the ensure fails
   * instead.
   */
  @Test
  void ananswerAboutTheRequestIsOneAttemptAndFailsTheEnsure() {
    credentials.enable();
    credentials.failCommissions(
        99, new AgentCredentialException("qits-idp answered 400", false));

    AgentCredentialException thrown =
        assertThrows(AgentCredentialException.class, this::fresh);

    assertEquals(List.of("commission:" + projectId), credentials.calls());
    assertTrue(thrown.getMessage().contains("after 1 attempt"), thrown.getMessage());
    assertFalse(
        DomainException.class.isAssignableFrom(thrown.getClass()),
        "a DomainException would be rethrown with a status; this must land in the FAILED arm");
  }

  /**
   * And that is what the ladder does with it: 200 with {@code FAILED} and the reason on {@code
   * failureDetail}, the same overlay a provision that failed uses. A panel told "the runtime broke"
   * can offer a retry; a 500 could not.
   */
  @Test
  void aCommissioningFailureSurfacesThroughTheProvisionFailureOverlay() {
    String id = createProject();
    runtime.failNextRun(
        new AgentCredentialException(
            "Could not commission a credential for the agent container of project "
                + id
                + " after 3 attempt(s): qits-idp unreachable",
            true));

    given()
        .when()
        .post("/projects/api/projects/" + id + "/agent-container/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("FAILED"))
        .body(
            "container.failureDetail",
            org.hamcrest.Matchers.containsString("Could not commission a credential"));
  }

  /** Handing a credential back is idempotent — a project holding none is not an error. */
  @Test
  void handingBackWhatIsNotHeldIsANoOp() {
    credentials.enable();

    commissions.handBack(projectId);

    assertEquals(List.of(), credentials.calls());
  }

  private String createProject() {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                "Commissioning " + UUID.randomUUID(), null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("project.id");
  }
}
