package eu.wohlben.qits.projects.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.AgentCredentialException;
import eu.wohlben.qits.projects.agenthost.AgentCredentials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What actually goes on the wire to qits-idp's commission API, and how each answer is read.
 *
 * <p>Plain JUnit against a stub, not a {@code @QuarkusTest}: this class is an {@link java.net.http.HttpClient}
 * and a classifier, and the cases worth pinning are the ones a real idp will not produce on demand —
 * a 401 in the middle of a cutover, a 404 on a client somebody already handed back, a connection
 * nothing accepts.
 *
 * <p>The classification is the sharp end. An answer about the <b>moment</b> is asked again inside
 * the window and an answer about the <b>request</b> is not, and getting that backwards is either an
 * ensure that fails across a cutover it should have survived or one that hangs for the whole window
 * on a 400 nothing will fix.
 */
class IdpAgentCredentialsTest {

  private static final String PROJECT = "11111111-2222-3333-4444-555555555555";

  private static final String COMMISSIONED =
      "{\"clientId\":\"dev-qits-projects-agent-7\",\"secret\":\"s3cr3t\",\"owner\":"
          + "\"dev-qits-projects\",\"contextKind\":\"agent-container\",\"contextId\":\""
          + PROJECT
          + "\",\"createdAt\":\"2026-08-14T10:00:00Z\"}";

  /** The bean as a deployment with an idp has it, pointed at a stub. */
  private static IdpAgentCredentials credentials(String authServerUrl) {
    IdpAgentCredentials credentials = new IdpAgentCredentials();
    credentials.objectMapper = new ObjectMapper();
    credentials.tokensEnabled = true;
    credentials.authServerUrl = authServerUrl;
    credentials.clientId = "dev-qits-projects";
    credentials.clientSecret = Optional.of("own-secret");
    credentials.requestTimeout = Duration.ofSeconds(2);
    return credentials;
  }

  @Test
  void aCommissionNamesTheContextAndAuthenticatesWithThisServicesOwnPair() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(201, COMMISSIONED);

      AgentCredentials.Commissioned pair = credentials(stub.url()).commission(PROJECT);

      assertEquals("dev-qits-projects-agent-7", pair.clientId());
      assertEquals("s3cr3t", pair.secret());
      StubIdpServer.Received request = stub.received().get(0);
      assertEquals("POST", request.method());
      assertEquals("/idp/api/clients", request.path());
      assertEquals(
          "Basic "
              + Base64.getEncoder()
                  .encodeToString("dev-qits-projects:own-secret".getBytes(StandardCharsets.UTF_8)),
          request.authorization(),
          "HTTP Basic with this service's own client credentials — the API's own mechanism");
      assertTrue(request.body().contains("\"contextKind\":\"agent-container\""), request.body());
      assertTrue(request.body().contains("\"contextId\":\"" + PROJECT + "\""), request.body());
      // And what the context is ABOUT: the project claim qits-idp puts on every token this pair
      // mints, which is what a resource service judges the agent container on. The same string as
      // the context id here, and a different fact — see AgentCredentials.commission.
      assertTrue(
          request.body().contains("\"claims\":{\"project\":\"" + PROJECT + "\"}"), request.body());
    }
  }

  /**
   * 401 and 403 are statements about the moment across an idp cutover — this service's own
   * credential belongs to the idp that was just replaced — so they are asked again. So is a 5xx, and
   * so is nothing answering.
   */
  @Test
  void anAnswerAboutTheMomentIsRetryable() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(401, "{\"error\":\"invalid_client\"}").answering(503, "{}");

      IdpAgentCredentials credentials = credentials(stub.url());
      assertTrue(
          assertThrows(AgentCredentialException.class, () -> credentials.commission(PROJECT))
              .retryable());
      assertTrue(
          assertThrows(AgentCredentialException.class, () -> credentials.commission(PROJECT))
              .retryable());
    }
  }

  /** Nothing listening is the same kind of answer, and the plainest one. */
  @Test
  void nothingAnsweringIsRetryable() {
    IdpAgentCredentials credentials = credentials("http://127.0.0.1:1/idp");

    assertTrue(
        assertThrows(AgentCredentialException.class, () -> credentials.commission(PROJECT))
            .retryable());
  }

  /** A 400 on a value is about the request, and no window fixes it. */
  @Test
  void anAnswerAboutTheRequestIsNot() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(400, "{\"error\":\"invalid_request\"}");

      assertFalse(
          assertThrows(
                  AgentCredentialException.class, () -> credentials(stub.url()).commission(PROJECT))
              .retryable());
    }
  }

  @Test
  void decommissioningAddressesTheClientAndToleratesOneAlreadyGone() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(204, "").answering(404, "{\"error\":\"not_found\"}");

      IdpAgentCredentials credentials = credentials(stub.url());
      credentials.decommission("dev-qits-projects-agent-7");
      // A client id idp no longer holds is the state this asks for, not a failure.
      credentials.decommission("dev-qits-projects-agent-7");

      assertEquals(
          List.of("/idp/api/clients/dev-qits-projects-agent-7"),
          stub.received().stream().map(StubIdpServer.Received::path).distinct().toList());
      assertEquals(
          List.of("DELETE", "DELETE"),
          stub.received().stream().map(StubIdpServer.Received::method).toList());
    }
  }

  /** The listing is this owner's own, and only the contexts this harness commissioned. */
  @Test
  void theListingKeepsOnlyAgentContainerContexts() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(
          200,
          "[{\"clientId\":\"a\",\"owner\":\"dev-qits-projects\",\"contextKind\":\"agent-container\","
              + "\"contextId\":\"p1\",\"createdAt\":\"2026-08-14T10:00:00Z\"},"
              + "{\"clientId\":\"b\",\"owner\":\"dev-qits-projects\",\"contextKind\":\"build-run\","
              + "\"contextId\":\"r1\",\"createdAt\":\"2026-08-14T10:00:00Z\"}]");

      assertEquals(
          List.of(new AgentCredentials.Commission("a", "p1")),
          credentials(stub.url()).listAgentContainerCommissions());
    }
  }

  /** An unreadable listing reconciles nothing rather than reporting an empty world. */
  @Test
  void anUnreadableListingIsEmpty() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(500, "nope");

      assertEquals(List.of(), credentials(stub.url()).listAgentContainerCommissions());
    }
  }

  /**
   * The shipped configuration, and the half-configured one. Neither commissions, and neither makes a
   * call — an address is not even built.
   */
  @Test
  void withNoCredentialOfItsOwnItIsDisabled() {
    IdpAgentCredentials off = credentials("http://127.0.0.1:1/idp");
    off.tokensEnabled = false;
    assertFalse(off.enabled());

    IdpAgentCredentials blank = credentials("http://127.0.0.1:1/idp");
    blank.clientSecret = Optional.of("   ");
    assertFalse(blank.enabled(), "the switch on with no secret is the same answer, with a warning");

    assertEquals(List.of(), off.listAgentContainerCommissions());
  }

  /** A base written with a trailing slash addresses the same route. */
  @Test
  void theBaseIsJoinedWithoutADoubleSlash() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(201, COMMISSIONED);

      credentials(stub.url() + "/").commission(PROJECT);

      assertEquals("/idp/api/clients", stub.received().get(0).path());
    }
  }
}
