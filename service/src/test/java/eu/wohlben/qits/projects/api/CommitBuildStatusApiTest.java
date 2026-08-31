package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The ledger end to end: a frame through the real listener, a row through the real migration, and
 * the answer off the repositories API — which is the read the release-quality-gates build gate will
 * make. The listener's own decode rules are {@code bus/BuildStatusListenerTest}; what this class
 * adds is the database and the route.
 */
@QuarkusTest
public class CommitBuildStatusApiTest {

  @Inject BuildStatusListener listener;

  private static EventFrame frame(String name, String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        name,
        Instant.parse("2026-08-30T12:00:00Z"),
        payload,
        null,
        null,
        null);
  }

  private static String payload(String runId, String repoId, String sha, String extra) {
    return "{\"branch\":\"main\",\"commitSha\":\""
        + sha
        + "\",\"repoId\":\""
        + repoId
        + "\",\"runId\":\""
        + runId
        + "\""
        + extra
        + "}";
  }

  @Test
  public void aCommitsVerdictsAreReadableWhereTheBuildGateWillAsk() {
    String repoId = UUID.randomUUID().toString();
    String sha = UUID.randomUUID().toString().replace("-", "");
    listener.onFrame(frame("BuildSuccessful", payload("run-green", repoId, sha, "")));
    listener.onFrame(
        frame("BuildFailed", payload("run-red", repoId, sha, ",\"outcome\":\"CONFIG_ERROR\"")));

    given()
        .get("/projects/api/repositories/" + repoId + "/commits/" + sha + "/builds")
        .then()
        .statusCode(200)
        .body("builds", hasSize(2))
        .body("builds.find { it.runId == 'run-green' }.status", equalTo("SUCCESS"))
        .body("builds.find { it.runId == 'run-red' }.status", equalTo("CONFIG_ERROR"))
        .body("builds.find { it.runId == 'run-red' }.branch", equalTo("main"));
  }

  @Test
  public void aRedeliveredVerdictConvergesOnOneRowPerRun() {
    String repoId = UUID.randomUUID().toString();
    String sha = UUID.randomUUID().toString().replace("-", "");
    listener.onFrame(frame("BuildSuccessful", payload("run-once", repoId, sha, "")));
    listener.onFrame(frame("BuildSuccessful", payload("run-once", repoId, sha, "")));

    given()
        .get("/projects/api/repositories/" + repoId + "/commits/" + sha + "/builds")
        .then()
        .statusCode(200)
        .body("builds", hasSize(1));
  }

  @Test
  public void aCommitNothingHasJudgedAnswersAnEmptyListRatherThanAnError() {
    given()
        .get(
            "/projects/api/repositories/"
                + UUID.randomUUID()
                + "/commits/deadbeef/builds")
        .then()
        .statusCode(200)
        .body("builds", hasSize(0));
  }
}
