package eu.wohlben.qits.projects.containershost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.TokenSource;
import eu.wohlben.qits.projects.agenthost.AgentContainerFactory;
import eu.wohlben.qits.projects.agenthost.ContainerRuntime.ContainerInfo;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import jakarta.enterprise.inject.Vetoed;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * How the agent runtime reads what the orchestrator answers, driven against
 * {@link StubContainersServer} on a real socket.
 *
 * <p><b>The claims here are all about the four answers, and each is a decision this class makes
 * rather than one the client makes for it.</b> A refusal and an unreachable service mean opposite
 * things to a refinement panel: one is evidence about the request, the other is evidence about
 * nothing at all. What acting on the difference looks like is what this file pins — a 404 is "there
 * is no container", anything else that is not a 2xx is a failure to report, and the two answers that
 * are about the moment rather than about the request are held through.
 *
 * <p>Plain JUnit with fields set by hand: nothing here needs a container, a database or an
 * application. The spec this sends is asserted where it is built,
 * {@code agenthost/AgentContainerFactoryTest}.
 */
class ContainersAgentRuntimeTest {

  private static final String PROJECT = "11111111-2222-3333-4444-555555555555";
  private static final String SLUG = "demo";
  private static final String NAME = "qits-proj-demo";
  private static final String WRAPPER = "demo-demo";

  /** An address nothing listens on — the only honest way to stage "nothing answered". */
  private static final String NOTHING_ANSWERS = "http://127.0.0.1:1";

  /**
   * A window a test can wait out. The pause is capped at the window, so this buys exactly one retry
   * — which is the whole of what these cases have to observe. A second rather than a few
   * milliseconds, because the window starts before the FIRST attempt (that is the shipped semantics,
   * not a test artefact) and the first attempt of a fresh client pays for the JDK client's setup.
   */
  private static final Duration ONE_RETRY = Duration.ofSeconds(1);

  /**
   * A {@link TokenSource} that counts how often it was asked, so a test can say that a retry asks
   * for a FRESH bearer rather than replaying the one that was just refused. That is the whole
   * mechanism by which a bring-up survives an idp cutover.
   */
  private static final class CountingTokens implements TokenSource {

    private final AtomicInteger asked = new AtomicInteger();

    @Override
    public Optional<String> bearer() {
      return Optional.of("token-" + asked.incrementAndGet());
    }

    int asked() {
      return asked.get();
    }
  }

  private final CountingTokens tokens = new CountingTokens();

  /**
   * The spec builder, stubbed down to the four values this class reads off it. What a real request
   * carries is asserted where it is built ({@code agenthost/AgentContainerFactoryTest}); what
   * matters here is only that the two bring-up arms send two different {@code recreate} words —
   * the wake permitting a replacement, the provision not.
   *
   * <p>{@code @Vetoed} is not optional. {@code @ApplicationScoped} is inherited, so without it this
   * subclass is a second {@code AgentContainerFactory} bean and every {@code @QuarkusTest} in the
   * module fails at {@code ArcProcessor#validate} with an ambiguous dependency.
   */
  @Vetoed
  private static final class TestFactory extends AgentContainerFactory {

    @Override
    public String containerName(String projectSlug) {
      return "qits-proj-" + projectSlug;
    }

    @Override
    public String projectVolumeName(String projectId) {
      return "qits_project_" + projectId;
    }

    @Override
    public EnsureRequest forProject(String projectId, String projectSlug, String repoName) {
      return EnsureRequest.of(spec(), Policy.idleStop(14400L));
    }

    @Override
    public EnsureRequest forRestart(String projectId, String projectSlug, String repoName) {
      return new EnsureRequest(spec(), Policy.idleStop(14400L), Recreate.ifChanged);
    }

    private static Spec spec() {
      return Spec.of("localhost:8081/qits/project-agent:test", "qits-net");
    }
  }

  private ContainersAgentRuntime runtime(String url) {
    return runtime(url, Duration.ZERO);
  }

  private ContainersAgentRuntime runtime(String url, Duration patience) {
    ContainersAgentRuntime runtime = new ContainersAgentRuntime();
    runtime.owner = "dev-qits-projects";
    runtime.ensurePatience = patience;
    runtime.factory = new TestFactory();
    // Deadlines a test can afford. The shipped ensure deadline is minutes, and it is an image pull.
    runtime.containers =
        new ContainersClient(url, Duration.ofSeconds(2), Duration.ofSeconds(5), tokens);
    return runtime;
  }

  /** An envelope body, as the orchestrator answers one. */
  private static String envelope(String name, String observed) {
    return "{\"id\":\"3f0f2b62-0000-4000-8000-000000000001\",\"containerName\":\""
        + name
        + "\",\"state\":{\"desired\":\"RUNNING\",\"observed\":\""
        + observed
        + "\"},\"created\":false}";
  }

  private static String listing(String... names) {
    StringBuilder body = new StringBuilder("{\"containers\":[");
    for (int i = 0; i < names.length; i++) {
      body.append(i == 0 ? "" : ",").append(envelope(names[i], "RUNNING"));
    }
    return body.append("]}").toString();
  }

  // --- reading a place ------------------------------------------------------------------------

  @Test
  void aRunningPlaceIsRunning() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, envelope(NAME, "RUNNING"));

      ContainerInfo info = runtime(stub.url()).inspect(PROJECT).orElseThrow();

      assertEquals(new ContainerInfo(NAME, true), info);
      assertEquals(
          "/containers/api/containers/dev-qits-projects/project-agent/" + PROJECT,
          stub.last().path(),
          "a place is addressed by the project id, which is the ref");
    }
  }

  /**
   * Every observed state but {@code RUNNING} reads as not running, and the ladder answers all of
   * them the same way — with a recreate. {@code PENDING} is the one worth naming: it is a bring-up
   * that died between two of the orchestrator's own writes, and reading it as running would leave
   * the place wedged forever.
   */
  @Test
  void everyOtherObservedStateIsNotRunning() throws Exception {
    for (String observed : List.of("PENDING", "STARTING", "EXITED", "MISSING", "GONE")) {
      try (StubContainersServer stub = new StubContainersServer()) {
        stub.script(200, envelope(NAME, observed));

        assertFalse(
            runtime(stub.url()).inspect(PROJECT).orElseThrow().running(),
            observed + " is not a container a panel can be opened onto");
      }
    }
  }

  @Test
  void aPlaceWithNoRowIsAbsent() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(404, "{\"code\":\"NOT_FOUND\",\"message\":\"no such place\"}");

      assertTrue(runtime(stub.url()).inspect(PROJECT).isEmpty());
    }
  }

  /**
   * The whole reason the client keeps a refusal and an unreachable service apart. Reading either as
   * "there is no container" would send the ladder to provision a second one against an answer nobody
   * gave — which is exactly what the docker CLI runtime this replaces did, since a broken binary and
   * an absent container both exited non-zero.
   */
  @Test
  void anOrchestratorThatCouldNotAnswerIsNotAnAbsentContainer() throws Exception {
    assertThrows(
        InternalServerErrorException.class, () -> runtime(NOTHING_ANSWERS).inspect(PROJECT));

    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(503, "{\"code\":\"UNAVAILABLE\",\"message\":\"the store is down\"}");

      assertThrows(
          InternalServerErrorException.class, () -> runtime(stub.url()).inspect(PROJECT));
    }
  }

  // --- bringing one up ------------------------------------------------------------------------

  @Test
  void provisioningChecksTheNameIsFreeAndThenEnsures() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, listing()).script(201, envelope(NAME, "RUNNING"));

      assertEquals(NAME, runtime(stub.url()).run(PROJECT, SLUG, WRAPPER));

      assertEquals(2, stub.received().size());
      assertEquals("GET", stub.received().get(0).method());
      assertEquals("PUT", stub.received().get(1).method());
      assertEquals(
          "/containers/api/containers/dev-qits-projects/project-agent/" + PROJECT,
          stub.received().get(1).path());
    }
  }

  /**
   * A container a deleted project left behind still holds the name a new project taking the freed
   * slug wants. The registry's container name is unique across every row it holds, so the ensure
   * would be refused anyway — as a constraint violation, which says nothing an operator can act on.
   * Asked first, the refusal names the way out and nothing is started.
   */
  @Test
  void aNameHeldByAnotherPlaceIsA409AndNothingIsStarted() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, listing(NAME));

      DomainException refused =
          assertThrows(
              DomainException.class, () -> runtime(stub.url()).run(PROJECT, SLUG, WRAPPER));

      assertEquals(409, refused.statusCode());
      assertTrue(refused.getMessage().contains(NAME));
      assertEquals(1, stub.received().size(), "the ensure is never sent");
    }
  }

  /** A listing this owner could not read is not a conflict: it fails open and the ensure decides. */
  @Test
  void anUnreadableListingDoesNotRefuseAProvision() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(503, "{\"code\":\"UNAVAILABLE\",\"message\":\"the store is down\"}")
          .script(201, envelope(NAME, "RUNNING"));

      assertEquals(NAME, runtime(stub.url()).run(PROJECT, SLUG, WRAPPER));
    }
  }

  /**
   * Waking a stopped place is <b>one</b> ensure and no name check — the place already holds its own
   * name, so the only thing a listing could find is itself. It permits a replacement without asking
   * for one: under an unchanged spec the orchestrator starts the container that is already there,
   * and only a spec that really differs is replaced.
   */
  @Test
  void bringingAStoppedPlaceBackIsOneEnsureAndNoNameCheck() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, envelope(NAME, "RUNNING"));

      assertEquals(NAME, runtime(stub.url()).restart(PROJECT, SLUG, WRAPPER));

      assertEquals(1, stub.received().size());
      assertEquals("PUT", stub.last().method());
      assertEquals(
          "/containers/api/containers/dev-qits-projects/project-agent/" + PROJECT,
          stub.last().path(),
          "the same place, addressed by the project id");
      assertTrue(stub.last().body().contains("\"recreate\":\"ifChanged\""));
    }
  }

  /**
   * <b>A 2xx whose container is not there is a failed bring-up.</b> The wire contract is explicit
   * that an ensure whose container did not start is a true answer rather than a failed request — the
   * row exists, it says MISSING, and it carries what docker said. Reading it as started would open a
   * refinement panel onto a container that never existed.
   */
  @Test
  void a2xxWhoseContainerIsMissingIsAFailure() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, listing())
          .script(
              200,
              "{\"id\":\"3f0f2b62-0000-4000-8000-000000000001\",\"containerName\":\""
                  + NAME
                  + "\",\"state\":{\"desired\":\"RUNNING\",\"observed\":\"MISSING\"},"
                  + "\"created\":false,\"detail\":\"[docker refused to start it]\"}");

      InternalServerErrorException failed =
          assertThrows(
              InternalServerErrorException.class,
              () -> runtime(stub.url()).run(PROJECT, SLUG, WRAPPER));

      assertTrue(failed.getMessage().contains("docker refused to start it"));
    }
  }

  // --- the two answers that are about the moment ------------------------------------------------

  /**
   * A 401 across an idp cutover is a statement about the moment rather than about the request: the
   * same call with the same owner succeeds a minute later, because the token or the key that
   * validates it has been replaced. Each attempt asks the token source again, which is the only way
   * a post-cutover token is ever picked up.
   */
  @Test
  void anAuthBlipIsHeldThroughAndTheNextAttemptAsksForAFreshToken() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, listing())
          .script(401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"unknown key\"}")
          .script(201, envelope(NAME, "RUNNING"));

      assertEquals(NAME, runtime(stub.url(), ONE_RETRY).run(PROJECT, SLUG, WRAPPER));

      assertEquals(3, stub.received().size());
      assertEquals(3, tokens.asked(), "a replayed bearer would be the one that was just refused");
    }
  }

  @Test
  void nothingAnsweringIsHeldThroughToo() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, listing()).scriptSilence().script(201, envelope(NAME, "RUNNING"));

      assertEquals(NAME, runtime(stub.url(), ONE_RETRY).run(PROJECT, SLUG, WRAPPER));
    }
  }

  /** Everything else is an answer about the request, and no window makes it a different one. */
  @Test
  void arefusalAboutTheRequestIsOneAttempt() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, listing())
          .script(409, "{\"code\":\"IMAGE_MISSING\",\"message\":\"no such image\"}");

      assertThrows(
          InternalServerErrorException.class,
          () -> runtime(stub.url(), ONE_RETRY).run(PROJECT, SLUG, WRAPPER));

      assertEquals(2, stub.received().size(), "a refusal about the request is not retried");
    }
  }

  // --- the sweep's input, and the two best-effort verbs ------------------------------------------

  @Test
  void theListingIsScopedToThisOwnersOwnWorkload() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, listing(NAME, "qits-proj-other"));

      assertEquals(
          List.of(new ContainerInfo(NAME, true), new ContainerInfo("qits-proj-other", true)),
          runtime(stub.url()).listAgentContainers());
      assertEquals(
          "/containers/api/containers/dev-qits-projects/project-agent",
          stub.last().path(),
          "the workload is a path segment, so this owner's other workloads are not listed");
    }
  }

  /** A listing nobody answered is not an empty host — the sweep does nothing rather than guessing. */
  @Test
  void anUnreadableListingSweepsNothing() {
    assertEquals(List.of(), runtime(NOTHING_ANSWERS).listAgentContainers());
  }

  @Test
  void stopAndTouchAreBestEffortAndNeverThrow() {
    ContainersAgentRuntime runtime = runtime(NOTHING_ANSWERS);

    runtime.stop(PROJECT);
    runtime.touch(PROJECT);
    runtime.ensureProjectVolume(PROJECT);
  }
}
