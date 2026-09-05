package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.ReleasedBranchWorkspaces;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code ReleasedBranchWorkspaces} absent is a supported configuration</b>, and this is the only
 * place it can be shown: every other suite in this module has both an implementation of the port on
 * the classpath (the {@code @DefaultBean} HTTP adapter) and a double beating it, so the injection
 * point is always resolvable there.
 *
 * <p>The absence is made by config rather than by a missing class — {@code quarkus.arc.exclude-types}
 * naming both implementations, which costs one extra augmentation and is why this is one small class
 * and not a second copy of the flow suite. What it proves is the sentence in the port's javadoc: a
 * release with nowhere to report its deleted branches still stamps, tags, deletes and settles
 * RELEASED, and the workspaces linger exactly as they did before the port existed.
 */
@QuarkusTest
@TestProfile(ReleaseWithNoWorkspaceResolutionTest.NoReleasedBranchWorkspacesProfile.class)
public class ReleaseWithNoWorkspaceResolutionTest {

  /** Both implementations gone, so the {@code Instance<T>} is genuinely unresolvable. */
  public static class NoReleasedBranchWorkspacesProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.arc.exclude-types",
          "eu.wohlben.qits.projects.testsupport.RecordingReleasedBranchWorkspaces,"
              + "eu.wohlben.qits.projects.workspacehost.HttpReleasedBranchWorkspaces");
    }
  }

  /** The premise of every assertion below, so a config override that stopped working says so. */
  @Inject Instance<ReleasedBranchWorkspaces> port;

  @Inject BuildStatusListener listener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    activeBuilds.answer(Optional.of(0));
    repoId = "no-workspaces-repo-" + UUID.randomUUID();
    projectId = "no-workspaces-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "no-workspace-resolution";
              project.slug = "no-workspace-resolution-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  @AfterEach
  void dropTheFixturesRequests() {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.delete("projectId = ?1", projectId));
  }

  @Test
  public void aReleaseSettlesWithNobodyToTellAboutItsDeletedBranches() {
    assertFalse(
        port.isResolvable(),
        "the point of this class is an unresolvable port; the exclude-types override stopped"
            + " working, and everything below would be asserting nothing");
    String base = "/projects/api/repositories/" + repoId + "/release-requests";
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"branch\":\"work\",\"summary\":\"a release with no workspaces context\"}")
            .post(base)
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    String merged =
        given().get(base + "/" + id).then().statusCode(200).extract().path("request.mergedSha");

    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\"work\",\"commitSha\":\"" + merged + "\",\"repoId\":\"" + repoId
                + "\",\"runId\":\"run-" + UUID.randomUUID() + "\"}",
            null,
            null,
            null));

    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = given().get(base + "/" + id).then().extract().path("request.state");
      if ("RELEASED".equals(last)) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    assertEquals("RELEASED", last, "an absent port must not stop a release settling");
    assertEquals(1, executor.calls().size(), "and the release itself happened exactly once");
    given().get(base + "/" + id).then().body("request.version", equalTo("2026.831.90000"));
  }
}
