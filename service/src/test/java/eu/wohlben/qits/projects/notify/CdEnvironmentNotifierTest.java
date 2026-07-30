package eu.wohlben.qits.projects.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.ProjectReconciliation;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wire half of the environment announcement: what actually leaves the process — method,
 * absolute path, payload — against a local server standing in for qits-cd. Plain JUnit over a
 * directly-constructed notifier; the seam's semantics (which creations announce, with which slug)
 * are held in {@code ProjectCreationHooksTest}.
 *
 * <p>The absolute address is asserted rather than the constant, because the failure this test
 * exists for is silent: a path that stopped matching cd's {@code /cd/api/environments} raises
 * nothing anywhere and environments simply stop appearing.
 *
 * <p>Delivery is fire-and-forget, so assertions wait on a queue the fixture fills rather than on
 * the call returning. The synchronous half at the bottom is the same server and the same fixture,
 * and that is the point: one request builder serves both paths, so both are asserted against one
 * wire.
 */
class CdEnvironmentNotifierTest {

  private record Received(String method, String path, Map<String, Object> body) {}

  private HttpServer server;
  private BlockingQueue<Received> received;
  private final AtomicInteger status = new AtomicInteger(201);

  @BeforeEach
  void startServer() throws IOException {
    received = new ArrayBlockingQueue<>(4);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed =
                body.length == 0 ? Map.of() : new ObjectMapper().readValue(body, Map.class);
            received.add(
                new Received(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), parsed));
          } finally {
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private CdEnvironmentNotifier notifier() {
    CdEnvironmentNotifier notifier = new CdEnvironmentNotifier();
    // Scheme, host and port only — the path is the notifier's, which is exactly what is under test.
    notifier.cdUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    notifier.objectMapper = new ObjectMapper();
    return notifier;
  }

  private Received await() throws InterruptedException {
    Received first = received.poll(10, TimeUnit.SECONDS);
    if (first == null) {
      fail("no environment creation arrived within the deadline");
    }
    return first;
  }

  @Test
  void theEnvironmentIsNamedAfterTheSlugOnMainWithNoApplications() throws Exception {
    notifier().onProjectCreated("project-1", "A Display Name", "a-display-name");

    Received request = await();
    assertEquals("POST", request.method());
    assertEquals("/cd/api/environments", request.path());
    assertEquals(
        Map.of("name", "a-display-name", "branch", "main", "applications", List.of()),
        request.body(),
        "the slug names the environment, not the display name");
  }

  /**
   * cd answering 409 means the environment already exists — the idempotent no-op a reconciling seed
   * produces on a later boot. Tolerated, not retried, and not surfaced.
   */
  @Test
  void a409IsToleratedAndNothingIsThrown() throws Exception {
    status.set(409);

    notifier().onProjectCreated("project-2", "Already There", "already-there");

    assertEquals("/cd/api/environments", await().path(), "the request was still made");
  }

  @Test
  void anUnreachableCdNeitherBlocksNorThrows() {
    CdEnvironmentNotifier notifier = new CdEnvironmentNotifier();
    // A TEST-NET address nothing answers on: the 2s connect timeout belongs to the async send, so
    // the
    // call itself has to return at once — it runs on the thread that just created a project.
    notifier.cdUrl = "http://192.0.2.1:9";
    notifier.objectMapper = new ObjectMapper();

    long before = System.nanoTime();
    notifier.onProjectCreated("project-3", "Nowhere", "nowhere");
    long elapsedMillis = (System.nanoTime() - before) / 1_000_000;

    assertTrue(
        elapsedMillis < 1_000,
        "fire-and-forget must not park the creating request (" + elapsedMillis + "ms)");
  }

  // --- the synchronous half, for the reconcile (main-environment-plan.md §5) ---

  /**
   * The same request, waited on: the reconcile must send what the creation path sends, which is
   * what makes the remedy a remedy rather than a second, differently-wrong announcement.
   */
  @Test
  void ensureEnvironmentSendsTheSameRequestAndReportsCreatedOn201() throws Exception {
    status.set(201);

    var assertion = notifier().ensureEnvironment("project-4", "Sync Create", "sync-create");

    assertEquals(ProjectReconciliation.EnvironmentOutcome.CREATED, assertion.outcome());
    assertNull(assertion.detail(), "a created environment needs no explanation");
    Received request = await();
    assertEquals("POST", request.method());
    assertEquals("/cd/api/environments", request.path());
    assertEquals(
        Map.of("name", "sync-create", "branch", "main", "applications", List.of()), request.body());
  }

  /** 409 is the environment already being there — the steady state a reconcile expects to find. */
  @Test
  void ensureEnvironmentReportsAlreadyExistsOn409() throws Exception {
    status.set(409);

    var assertion = notifier().ensureEnvironment("project-5", "Sync Steady", "sync-steady");

    assertEquals(ProjectReconciliation.EnvironmentOutcome.ALREADY_EXISTS, assertion.outcome());
    assertNull(assertion.detail());
    assertEquals("/cd/api/environments", await().path(), "the request was still made");
  }

  /** Anything else is a failure that names the status code, because the caller can act on it. */
  @Test
  void ensureEnvironmentReportsFailedWithTheStatusCode() throws Exception {
    status.set(500);

    var assertion = notifier().ensureEnvironment("project-6", "Sync Broken", "sync-broken");

    assertEquals(ProjectReconciliation.EnvironmentOutcome.FAILED, assertion.outcome());
    assertTrue(assertion.detail().contains("500"), "got: " + assertion.detail());
    assertTrue(assertion.detail().contains("sync-broken"));
    await();
  }

  /**
   * Unreachable is an outcome and never an exception — and it is <b>bounded</b>, because this one
   * runs on a request thread with a person on the other end of it.
   */
  @Test
  void ensureEnvironmentReportsFailedWhenCdIsUnreachable() {
    CdEnvironmentNotifier notifier = new CdEnvironmentNotifier();
    notifier.cdUrl = "http://192.0.2.1:9";
    notifier.objectMapper = new ObjectMapper();

    long before = System.nanoTime();
    var assertion = notifier.ensureEnvironment("project-7", "Nowhere", "nowhere");
    long elapsedMillis = (System.nanoTime() - before) / 1_000_000;

    assertEquals(ProjectReconciliation.EnvironmentOutcome.FAILED, assertion.outcome());
    assertTrue(assertion.detail().contains("unreachable"), "got: " + assertion.detail());
    assertTrue(
        elapsedMillis < 10_000,
        "the connect timeout is the deadline a caller waits on (" + elapsedMillis + "ms)");
  }
}
