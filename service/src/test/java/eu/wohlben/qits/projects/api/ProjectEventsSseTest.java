package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.api.ProjectEpicsController;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The project events endpoint end to end: a browser subscribes, somebody mutates an epic, and the
 * {@code epics} hint arrives as an SSE frame. This is the whole point of the channel — the epics
 * overview re-fetches on the frame rather than polling — so it is asserted over real HTTP against
 * the real CDI async bus, not by calling the broadcaster.
 */
@QuarkusTest
class ProjectEventsSseTest {

  @TestHTTPResource("/projects/api/projects/")
  URL projectsUrl;

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  @Test
  void anEpicMutationReachesTheProjectsSubscribers() throws Exception {
    String projectId = createProject("SSE Project");

    assertSseDataFrame(
        new URL(projectsUrl, projectId + "/events"),
        () ->
            given()
                .contentType(ContentType.JSON)
                .body(new ProjectEpicsController.CreateEpicRequest("Drafted live", "The spine"))
                .when()
                .post("/projects/api/projects/" + projectId + "/epics")
                .then()
                .statusCode(200),
        "epics");
  }

  @Test
  void anUnknownProjectHasNoChannel() {
    // The subscribe resolves the id against the database — which is what @Blocking on the route is
    // for, and what turns a bad id into a 404 instead of an empty stream nobody ever gets a frame
    // on.
    given()
        .header("Accept", "text/event-stream")
        .when()
        .get("/projects/api/projects/does-not-exist/events")
        .then()
        .statusCode(404);
  }

  /** Open {@code url} as an SSE stream, run {@code mutate}, and expect a {@code data: expected}. */
  private void assertSseDataFrame(URL url, Runnable mutate, String expected) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(url.toURI())
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

    // Open the stream; when send() returns, the server has begun the response and subscribed.
    HttpResponse<InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                  lines.add(line);
                }
              } catch (Exception ignored) {
                // stream closed at test teardown — expected
              }
            });
    reader.setDaemon(true);
    reader.start();

    Thread.sleep(400); // let the subscription settle before mutating
    mutate.run();

    // Read frames until the expected data line arrives (ignoring heartbeat/blank/comment lines).
    long deadline = System.currentTimeMillis() + 5000;
    boolean seen = false;
    long remaining;
    while (!seen && (remaining = deadline - System.currentTimeMillis()) > 0) {
      String line = lines.poll(remaining, TimeUnit.MILLISECONDS);
      if (line != null && line.startsWith("data:") && line.substring(5).trim().equals(expected)) {
        seen = true;
      }
    }
    assertTrue(seen, "expected a 'data: " + expected + "' SSE frame over HTTP");
  }
}
