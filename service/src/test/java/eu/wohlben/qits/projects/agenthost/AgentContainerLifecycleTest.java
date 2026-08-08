package eu.wohlben.qits.projects.agenthost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projectsdaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.projectsdaemon.protocol.Provisioned;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agent-container lifecycle, over the REST surface an SPA panel drives it from.
 *
 * <p>Two things are under test and they are not the same thing. The <b>ladder</b> is which docker
 * verb runs — nothing for a running container, {@code start} for a stopped one, {@code run} for an
 * absent one — and it is asserted against {@link FakeContainerRuntime#calls()}, because a test that
 * only read the answering status would pass just as happily for a ladder that re-ran a container it
 * should have started, losing nothing visible and every uncommitted file in the checkout.
 *
 * <p>The <b>response shape</b> is a published contract consumed by a client written in another
 * repository, so it is asserted field by field: {@code container.runtimeStatus}, {@code
 * container.daemonConnected}, {@code container.daemonVersion}, identically from all three routes.
 */
@QuarkusTest
class AgentContainerLifecycleTest {

  @Inject FakeContainerRuntime runtime;

  @Inject AgentContainers agentContainers;

  @Inject AgentDaemonRegistry registry;

  private String projectId;
  private String containerName;

  @BeforeEach
  void setUp() {
    runtime.reset();
    String name = "Agent Ladder " + UUID.randomUUID();
    io.restassured.path.json.JsonPath created =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    name, null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    projectId = created.getString("project.id");
    containerName = "qits-proj-" + created.getString("project.slug");
  }

  private String base() {
    return "/projects/api/projects/" + projectId + "/agent-container";
  }

  @Test
  void absentProvisions() {
    given()
        .when()
        .post(base() + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("RUNNING"))
        .body("container.daemonConnected", org.hamcrest.Matchers.is(false))
        .body("container.daemonVersion", org.hamcrest.Matchers.nullValue());

    assertTrue(
        runtime.calls().stream().anyMatch(call -> call.startsWith("run:" + containerName + ":")),
        "an absent container is provisioned, not started");
    assertTrue(
        runtime.volumes().contains("qits_project_" + projectId),
        "the labelled checkout volume is created before the container mounts it");
  }

  @Test
  void runningIsANoOp() {
    runtime.given(containerName, projectId, true);

    agentContainers.ensure(projectId);

    assertEquals(
        java.util.List.of(), runtime.calls(), "a running container is left exactly as it was");
  }

  @Test
  void stoppedIsStartedInPlace() {
    runtime.given(containerName, projectId, false);

    agentContainers.ensure(projectId);

    assertEquals(
        java.util.List.of("start:" + containerName),
        runtime.calls(),
        "a stopped container is started, never re-run — the checkout has to survive");
  }

  @Test
  void aFailedProvisionAnswersFailed() {
    runtime.failNextRun(new IllegalStateException("no such image"));

    given()
        .when()
        .post(base() + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("FAILED"))
        .body("container.failureDetail", org.hamcrest.Matchers.is("no such image"));
  }

  /**
   * The container is up, docker calls it healthy, and its {@code /workspace} is empty — the state
   * this read used to describe as {@code RUNNING}, which sent the panel to open a terminal onto
   * nothing. The daemon's word outranks docker's until it takes it back.
   *
   * <p>The frame is delivered with a null connection: the {@code ProvisionFailed} branch reads the
   * message and nothing else, so a socket here would be fixture with no assertion behind it.
   */
  @Test
  void aFailedProvisionMakesARunningContainerReadFailed() {
    runtime.given(containerName, projectId, true);
    registry.onMessage(
        projectId, null, new ProvisionFailed(projectId, "clone refused: no such remote"));

    given()
        .when()
        .get(base())
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("FAILED"))
        .body("container.failureDetail", org.hamcrest.Matchers.is("clone refused: no such remote"));

    // A retry that works takes it back, and the panel is usable again with no restart anywhere.
    registry.onMessage(projectId, null, new Provisioned(projectId, "abc123"));

    given()
        .when()
        .get(base())
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("RUNNING"))
        .body("container.failureDetail", org.hamcrest.Matchers.nullValue());
  }

  @Test
  void stopIsGracefulAndIdempotent() {
    runtime.given(containerName, projectId, true);

    given()
        .when()
        .post(base() + "/stop")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("STOPPED"));

    assertEquals(
        java.util.List.of("stop:" + containerName),
        runtime.calls(),
        "stop, never remove: the container and its /workspace volume survive");

    // A second stop finds it already down and says so rather than failing.
    given()
        .when()
        .post(base() + "/stop")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("STOPPED"));
  }

  @Test
  void readAnswersAbsentWithNoContainer() {
    given()
        .when()
        .get(base())
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("ABSENT"))
        .body("container.daemonConnected", org.hamcrest.Matchers.is(false));
  }

  @Test
  void readAnswersTheSameShapeAsEnsure() {
    runtime.given(containerName, projectId, false);

    given()
        .when()
        .get(base())
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("STOPPED"))
        .body("container.daemonConnected", org.hamcrest.Matchers.is(false))
        .body("container.daemonVersion", org.hamcrest.Matchers.nullValue())
        .body("container.failureDetail", org.hamcrest.Matchers.nullValue());
  }

  @Test
  void anUnknownProjectIs404OnEveryRoute() {
    String unknown = "/projects/api/projects/" + UUID.randomUUID() + "/agent-container";
    given().when().get(unknown).then().statusCode(404);
    given().when().post(unknown + "/ensure").then().statusCode(404);
    given().when().post(unknown + "/stop").then().statusCode(404);
  }

  @Test
  void aContainerBelongingToAnotherProjectIsRefused() {
    // Two projects can share a slug — Project.slug is deliberately not unique — so the name alone
    // proves nothing. Adopting a foreign container would hand this project a shell over somebody
    // else's checkout.
    runtime.given(containerName, "some-other-project", true);

    given().when().post(base() + "/ensure").then().statusCode(409);
    assertEquals(java.util.List.of(), runtime.calls(), "nothing is touched on the refusal");
  }
}
