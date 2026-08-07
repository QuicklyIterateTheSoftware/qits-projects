package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * The git host's post-receive intake.
 *
 * <p>Every case here asserts the same thing from a different angle, because for this route that is
 * the contract: <b>204, always</b>. The sender is a git hook running inside somebody's {@code git
 * push} — it cannot act on an error, it cannot wait, and a 4xx here would turn an irrelevant fact
 * into a failed-looking push. What the event actually causes is asserted where it happens, in {@code
 * BackupPushServiceTest}.
 */
@QuarkusTest
public class GitHostEventControllerTest {

  private static final String PATH = "/projects/api/events/post-receive";

  private io.restassured.response.Response post(String body) {
    return given().contentType(ContentType.JSON).body(body).when().post(PATH);
  }

  @Test
  public void aPostReceiveIsAcceptedWithNoBody() {
    post(
            "{\"repoId\":\"some-repository\",\"branch\":\"main\","
                + "\"oldSha\":\"1111111111111111111111111111111111111111\","
                + "\"newSha\":\"2222222222222222222222222222222222222222\"}")
        .then()
        .statusCode(Response.Status.NO_CONTENT.getStatusCode())
        .body(org.hamcrest.Matchers.emptyOrNullString());
  }

  /** This service is not the authority on which repositories the host serves. */
  @Test
  public void anUnknownRepositoryIsAcceptedAndDropped() {
    post("{\"repoId\":\"no-such-repository-anywhere\",\"branch\":\"main\"}")
        .then()
        .statusCode(Response.Status.NO_CONTENT.getStatusCode());
  }

  @Test
  public void aBranchDeleteIsJustAnotherEvent() {
    post(
            "{\"repoId\":\"some-repository\",\"branch\":\"gone\","
                + "\"oldSha\":\"2222222222222222222222222222222222222222\","
                + "\"newSha\":\"0000000000000000000000000000000000000000\"}")
        .then()
        .statusCode(Response.Status.NO_CONTENT.getStatusCode());
  }

  @Test
  public void aBodyMissingEverythingTheHookCouldNotSupplyIsStillAccepted() {
    post("{}").then().statusCode(Response.Status.NO_CONTENT.getStatusCode());
    post("{\"repoId\":null}").then().statusCode(Response.Status.NO_CONTENT.getStatusCode());
  }

  /**
   * An unknown field is what a sender one version ahead looks like. It must not be a 400: the hook
   * would have no way to tell, and the fields this route reads are still all there.
   */
  @Test
  public void aFieldThisServiceDoesNotKnowIsIgnoredRatherThanRefused() {
    post("{\"repoId\":\"some-repository\",\"branch\":\"main\",\"pusher\":\"someone\"}")
        .then()
        .statusCode(Response.Status.NO_CONTENT.getStatusCode());
  }
}
