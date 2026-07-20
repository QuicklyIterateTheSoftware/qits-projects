package eu.wohlben.qits.domain.repository.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.repository.entity.PromptAttachmentSource;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptAttachmentRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(WorkspacePromptAttachmentControllerTest.TestProfile.class)
public class WorkspacePromptAttachmentControllerTest {

  /**
   * A tiny {@code prompt-attachment-max-bytes} cap so the oversize case needs only a small image.
   */
  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-repos");
        return Map.of(
            "qits.repositories.data-dir",
            tempDir.toString(),
            "qits.workspace.prompt-attachment-max-bytes",
            "64");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  // Minimal but valid magic-byte prefixes — the sniff reads only the leading signature.
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03
  };
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x11, 0x22};

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspacePromptAttachmentRepository attachmentRepository;

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private final String fixtureUrl;

  public WorkspacePromptAttachmentControllerTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Prompt Attachment Project", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    return given()
        .contentType(ContentType.JSON)
        .body(
            new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, null))
        .when()
        .post("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  /**
   * The attachments collection for a workspace (the fresh repo always has a "master" workspace).
   */
  private String attachmentsUrl(String repoId, String workspaceId) {
    return "/api/repositories/"
        + repoId
        + "/workspaces/"
        + workspaceId
        + "/prompt-draft/attachments";
  }

  private void createWorkspace(String repoId, String id, String parent, String branch) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(id, parent, branch, null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  private WorkspacePromptDraftController.AddAttachmentRequest attach(
      String mimeType, String label, String source, byte[] bytes) {
    return new WorkspacePromptDraftController.AddAttachmentRequest(
        mimeType, label, source, Base64.getEncoder().encodeToString(bytes));
  }

  private Long workspacePk(String repoId, String workspaceId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                workspaceRepository
                    .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
                    .orElseThrow()
                    .id);
  }

  @Test
  public void postValidPngPersistsRowWithSniffedTypeSourceAndLabel() {
    String repoId = createProjectAndRepository();

    String id =
        given()
            .contentType(ContentType.JSON)
            .body(attach("image/png", "Sketch 1", "sketch", PNG))
            .when()
            .post(attachmentsUrl(repoId, "master"))
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("id", not(emptyOrNullString()))
            .extract()
            .path("id");

    Long pk = workspacePk(repoId, "master");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var row = attachmentRepository.findByWorkspaceIdAndId(pk, id).orElseThrow();
              assertEquals("image/png", row.mimeType);
              assertEquals("Sketch 1", row.label);
              assertEquals(PromptAttachmentSource.SKETCH, row.source);
            });
  }

  @Test
  public void postValidJpegStoresImageJpeg() {
    String repoId = createProjectAndRepository();

    String id =
        given()
            .contentType(ContentType.JSON)
            .body(attach("image/jpeg", "Pasted image 1", "paste", JPEG))
            .when()
            .post(attachmentsUrl(repoId, "master"))
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("id");

    Long pk = workspacePk(repoId, "master");
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                assertEquals(
                    "image/jpeg",
                    attachmentRepository.findByWorkspaceIdAndId(pk, id).orElseThrow().mimeType));
  }

  @Test
  public void sniffedTypeWinsOverClaimedMimeType() {
    String repoId = createProjectAndRepository();

    // JPEG bytes but a lying "image/png" claim — the bytes decide, so the row is image/jpeg.
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(attach("image/png", "Sketch 1", "sketch", JPEG))
            .when()
            .post(attachmentsUrl(repoId, "master"))
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("id");

    Long pk = workspacePk(repoId, "master");
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                assertEquals(
                    "image/jpeg",
                    attachmentRepository.findByWorkspaceIdAndId(pk, id).orElseThrow().mimeType));
  }

  @Test
  public void nonImageBytesReturn400() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(attach("image/png", "Sketch 1", "sketch", "hello".getBytes()))
        .when()
        .post(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void invalidBase64Returns400() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspacePromptDraftController.AddAttachmentRequest(
                "image/png", "x", "sketch", "not!!base64"))
        .when()
        .post(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void unknownSourceReturns400() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(attach("image/png", "x", "clipboard", PNG))
        .when()
        .post(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void absentRequestBodyReturns400() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .post(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void oversizeImageReturns413() {
    String repoId = createProjectAndRepository();

    // A valid PNG signature, but the decoded payload blows the 64-byte test cap.
    byte[] big = new byte[80];
    System.arraycopy(PNG, 0, big, 0, PNG.length);

    given()
        .contentType(ContentType.JSON)
        .body(attach("image/png", "Sketch 1", "sketch", big))
        .when()
        .post(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
  }

  @Test
  public void unknownRepositoryReturns404() {
    given()
        .contentType(ContentType.JSON)
        .body(attach("image/png", "x", "sketch", PNG))
        .when()
        .post(attachmentsUrl("no-such-repo", "master"))
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void unknownWorkspaceReturns404() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(attach("image/png", "x", "sketch", PNG))
        .when()
        .post(attachmentsUrl(repoId, "no-such-workspace"))
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void listReturnsRowsOldestFirstWithDataBase64Roundtripped() {
    String repoId = createProjectAndRepository();

    String firstData = Base64.getEncoder().encodeToString(PNG);
    String secondData = Base64.getEncoder().encodeToString(JPEG);

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspacePromptDraftController.AddAttachmentRequest(
                "image/png", "Sketch 1", "sketch", firstData))
        .when()
        .post(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspacePromptDraftController.AddAttachmentRequest(
                "image/jpeg", "Pasted image 1", "paste", secondData))
        .when()
        .post(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .when()
        .get(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("size()", is(2))
        // Oldest first (attach order); each row carries its base64 payload byte-identical to input.
        .body("[0].label", is("Sketch 1"))
        .body("[0].mimeType", is("image/png"))
        .body("[0].source", is("SKETCH"))
        .body("[0].dataBase64", is(firstData))
        .body("[1].label", is("Pasted image 1"))
        .body("[1].source", is("PASTE"))
        .body("[1].dataBase64", is(secondData));
  }

  @Test
  public void listReturnsEmptyArrayWhenNoneAttached() {
    String repoId = createProjectAndRepository();

    given()
        .when()
        .get(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("size()", is(0));
  }

  @Test
  public void listUnknownWorkspaceReturns404() {
    String repoId = createProjectAndRepository();

    given()
        .when()
        .get(attachmentsUrl(repoId, "no-such-workspace"))
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void deleteRemovesTheAttachment() {
    String repoId = createProjectAndRepository();

    String id =
        given()
            .contentType(ContentType.JSON)
            .body(attach("image/png", "Sketch 1", "sketch", PNG))
            .when()
            .post(attachmentsUrl(repoId, "master"))
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("id");

    given()
        .when()
        .delete(attachmentsUrl(repoId, "master") + "/" + id)
        .then()
        .statusCode(Response.Status.NO_CONTENT.getStatusCode());

    Long pk = workspacePk(repoId, "master");
    assertFalse(
        QuarkusTransaction.requiringNew()
            .call(() -> attachmentRepository.findByWorkspaceIdAndId(pk, id).isPresent()),
        "attachment row must be gone after delete");
  }

  @Test
  public void deleteUnknownAttachmentReturns404() {
    String repoId = createProjectAndRepository();

    given()
        .when()
        .delete(attachmentsUrl(repoId, "master") + "/no-such-attachment")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void deleteAcrossWorkspacesIsRejectedAndLeavesTheRow() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "other-ws", "master", "other-branch");

    String id =
        given()
            .contentType(ContentType.JSON)
            .body(attach("image/png", "Sketch 1", "sketch", PNG))
            .when()
            .post(attachmentsUrl(repoId, "master"))
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("id");

    // Master's attachment cannot be deleted through other-ws's collection.
    given()
        .when()
        .delete(attachmentsUrl(repoId, "other-ws") + "/" + id)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());

    Long masterPk = workspacePk(repoId, "master");
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> attachmentRepository.findByWorkspaceIdAndId(masterPk, id).isPresent()),
        "the attachment must survive a cross-workspace delete attempt");
  }

  @Test
  public void deletingTheDraftEmptiesTheAttachmentRows() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(attach("image/png", "Sketch 1", "sketch", PNG))
        .when()
        .post(attachmentsUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // DELETE on the parent draft resource clears its attachment rows with it.
    given()
        .when()
        .delete("/api/repositories/" + repoId + "/workspaces/master/prompt-draft")
        .then()
        .statusCode(Response.Status.NO_CONTENT.getStatusCode());

    Long pk = workspacePk(repoId, "master");
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> attachmentRepository.listByWorkspaceId(pk).isEmpty()),
        "attachment rows must be empty after the draft is deleted");
  }

  @Test
  public void discardingTheWorkspaceDeletesItsAttachmentsLeavingNoOrphan() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "att-ws", "master", "att-branch");

    given()
        .contentType(ContentType.JSON)
        .body(attach("image/png", "Sketch 1", "sketch", PNG))
        .when()
        .post(attachmentsUrl(repoId, "att-ws"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    Long pk = workspacePk(repoId, "att-ws");
    assertFalse(
        QuarkusTransaction.requiringNew()
            .call(() -> attachmentRepository.listByWorkspaceId(pk).isEmpty()),
        "attachment should exist before discard");

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.DiscardWorkspaceRequest(null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/att-ws/discard")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // Regression: the workspace is only soft-deleted, so the row cascade never fires — the
    // attachment rows must have been deleted explicitly, leaving no orphan.
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> attachmentRepository.listByWorkspaceId(pk).isEmpty()),
        "attachment rows must be gone after discard");
  }
}
