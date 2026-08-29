package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The whole service as it is <b>packaged</b> — like {@link PackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove: the shipped tenant is
 * gated behind {@code qits.auth.machine.required} and every suite in this repository leaves that
 * gate shut, so the {@code quarkus.oidc.*} block this service actually deploys with
 * (auth-server-url + jwks-path against a real listener, audience enforcement, the {@code groups}
 * claim becoming roles) is exercised nowhere else. The far side is {@link MockIdp}, whose
 * recordings make the interaction assertable on <b>both ends</b>.
 *
 * <p>It is also this repo's <b>first userflow</b>, and the one every other story class in
 * {@code stories/} is ordered after: the proof doubles as documentation, emitted under {@code
 * target/userstories/} with a network diagram beside the steps. The diagram is <b>observed, never
 * narrated</b> — the framework's shipped {@link NetworkTaps#restAssured(String) RestAssured tap}
 * draws what a story sends into this service, {@link MockIdp}'s recordings supply what this service
 * sent to the idp, and the framework drains both at story end. A story method therefore asserts and
 * notes; it draws nothing. The story is browserless (no {@code Flow} parameter), so no Chromium is
 * involved anywhere.
 *
 * <p><b>The two stories are ordered</b>, and that is load-bearing rather than tidiness: a
 * cumulative source is attributed by a cursor, so traffic that happened before any story ran — the
 * startup JWKS fetch, which is the whole subject of the first story — lands in whichever story
 * drains <i>first</i>. Pinning the order is what keeps that the story it belongs to. The same
 * reasoning is why this class runs before every other: it owns the startup traffic, and the story
 * classes under {@code stories/} declare {@code @UserflowRunsAfter(TokenValidationBootstrapIT)} so
 * that stays true however the packages are later renamed.
 *
 * <p><b>ITs are skipped by default here and this one does NOT flip that</b>, unlike qits-githost's
 * namesake. {@code skipITs} is {@code true} in the root pom because {@link PackagedSurfaceIT} is
 * the repository's other IT and it is heavyweight — real git pushes through a CGI fixture and a
 * pseudo-terminal — so opting the module back in globally would drag that into every {@code mvn
 * verify}. {@code .config/qits/ci-event-userflows.yml} names this class instead
 * ({@code -DskipITs=false "-Dit.test=TokenValidationBootstrapIT"}), which is also what keeps the
 * userflow pipeline about this story and nothing else.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(GitHostFixture.class)
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG =
      "on-start-the-projects-service-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-projects-catalogue";

  /** How the diagram names this service on both sides of an edge. */
  static final String SERVICE = "qits-projects";

  /**
   * {@link PackagedSurfaceIT.PackagedResources} — the three {@code QITS_RESOURCE_*} triples on this
   * JVM's embedded postgres, parked in system properties because a test profile is instantiated in
   * more than one classloader — <b>plus the two things this story is about</b>: the gate that turns
   * the shipped OIDC tenant on, and where the idp is.
   *
   * <p>Extending rather than copying is deliberate. What a launched qits-projects needs in order to
   * boot at all is one answer, it is written out at length over there, and a second copy of the
   * parking trick would be a second place for it to drift. What is added here is only the seam this
   * test moves.
   *
   * <p>The mock idp starts <b>before</b> the application, via {@link MockIdp#ensureStarted()},
   * which parks its coordinates (and its keypair) in system properties for the same classloader
   * reason — that is also how the story method's {@link MockIdp#attach()} reaches the very server
   * the launched process fetched its keys from.
   *
   * <p>Every key below is a RUNTIME key. A packaged process takes its configuration as {@code -D}
   * arguments on a jar that was already built, so a build-time key here would be silently ignored
   * and the test would prove the opposite of what it says.
   */
  public static class PackagedWithMockIdp extends PackagedSurfaceIT.PackagedResources {

    /**
     * The audience this service enforces, and it is a LITERAL rather than a variable name — the
     * difference from qits-githost's IT, which hands its launched process {@code
     * QITS_AUTH_MACHINE_AUDIENCE} because the shipped expression there reads that variable. Here
     * {@code qits.auth.machine.audience=qits-projects} is spelled out in
     * {@code application.properties}, so the audience under test is the shipped one and there is no
     * expression to feed. A deployment still overrides it by environment.
     */
    static final String AUDIENCE = "qits-projects";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());
      // THE GATE, and turning it on is the point: the shipped tenant is
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}, so this one key is the
      // difference between a service that validates machine bearers and one that does not. It is
      // the posture a deployed platform takes for the commissioned agent's control socket, and
      // this story is where it is documented.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam this test moves: where the idp is. Runtime key, so the packaged artifact is
      // otherwise exactly what ships — discovery stays off and jwks-path stays `jwks`.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      // Dark outside a deployment, like %dev/%test — both are runtime keys. The eventstream
      // DATASOURCE is still opened and migrated (dark stops publishing, sweeping and dialling,
      // never the datasource), which is why the third triple above is not optional.
      overrides.put("quarkus.otel.sdk.disabled", "true");
      overrides.put("qits.eventstream.enabled", "false");
      // The self-seed reconciles this platform's own repositories against GitHub, on a virtual
      // thread, on every NORMAL-mode boot — which a packaged IT is. It is off here because it
      // reaches a network the CI step container has no business needing and because it would put
      // rows behind the catalogue this story reads: an empty catalogue is a 200 too, and a story
      // about who may READ it is clearer when what it answers is nobody's incidental state.
      overrides.put("qits.startup-seed.enabled", "false");
      // THE OUTBOUND HALF, and it is what lets the catalogue stories exist at all.
      // HttpGitHostRepositories fails CLOSED: every lifecycle call to qits-githost asks
      // IdpGitHostBearer for a machine token first and throws "No machine bearer is available"
      // rather than sending one unauthenticated. The shipped default is
      // quarkus.oidc-client.githost.client-enabled=false, so a packaged process with no idp cannot
      // create a repository at all — which is correct in production and is why every story that
      // publishes to the git host needs this named client pointed at the same MockIdp the inbound
      // tenant validates against. The token itself is a stub on that mock (see
      // {@link #stubTheGitHostTokenEndpoint}); the git-host fixture does not check it, because what
      // is under test here is that this service PRESENTS one.
      overrides.put("quarkus.oidc-client.githost.client-enabled", "true");
      overrides.put("quarkus.oidc-client.githost.auth-server-url", idp.baseUrl());
      overrides.put("quarkus.oidc-client.githost.credentials.secret", GITHOST_CLIENT_SECRET);
      stubTheGitHostTokenEndpoint(idp);
      return overrides;
    }
  }

  /** The secret this process authenticates its {@code githost} oidc-client with. Not a real one. */
  static final String GITHOST_CLIENT_SECRET = "story-githost-secret";

  /**
   * The idp's token endpoint — {@code auth-server-url} + {@code token-path}, joined the way
   * discovery-off configuration joins them. Named because the capture source below excludes it.
   */
  static final String TOKEN_PATH = "/idp/token";

  /**
   * Stub {@code POST /idp/token} so quarkus-oidc-client's {@code client_credentials} grant has
   * something to answer it — the one route a real idp serves that {@link MockIdp} does not stub on
   * its own, because minting on demand is a token endpoint's whole job and a mock cannot guess the
   * audience anybody will ask for.
   *
   * <p>The token is a real RS256 token signed by the mock's keypair, with the audience qits-githost
   * would enforce, so the answer is the shape a resource server would accept rather than a
   * placeholder string. It carries an hour, which outlives any story run, so exactly <b>one</b>
   * token fetch happens per run — and that fetch is an edge in whichever story first publishes to
   * the git host, which is where it belongs.
   *
   * <p>Only the copy of this class that <i>started</i> the mock may stub it; a second classloader's
   * copy attaches instead and the call throws, which is the signal that the owner already did it.
   */
  private static void stubTheGitHostTokenEndpoint(MockIdp idp) {
    Map<String, Object> answer =
        Map.of(
            "access_token",
            idp.token()
                .subject("qits-projects")
                .audience("qits-githost")
                .groups("qits:system")
                .ttl(Duration.ofHours(1))
                .mint(),
            "token_type",
            "Bearer",
            "expires_in",
            3600);
    try {
      idp.service().stub("POST", TOKEN_PATH, answer);
    } catch (IllegalStateException attachedRatherThanOwning) {
      // A test profile is instantiated in more than one classloader; only the one that started the
      // mock owns its stubs, and it has already registered this route.
    }
  }

  /**
   * Wires both halves of the network diagram, once, before either story runs.
   *
   * <p>{@link NetworkTaps#restAssured(String)} is the near side (what a story sends here) — the
   * framework's own tap since 2026.829, replacing the local {@code StoryNetworkFilter} four
   * repositories had each hand-copied. Its default skip is any path carrying a {@code /q/} segment,
   * which is right for this service: {@code quarkus.http.non-application-root-path} is {@code
   * /projects/q}, so the readiness probe below is out of every diagram and no route this service
   * owns is. It is idempotent per service, so a story class installing it again draws nothing
   * twice.
   *
   * <p>The idp is the far side, registered as a <b>cumulative</b> source: the supplier hands over
   * the mock's whole request log every time it is asked and the framework remembers how much of it
   * earlier stories already consumed, so the startup fetch — recorded long before any story existed
   * — is attributed to the first story and to that one only. It is invoked lazily at story end, so
   * registering it here is safe even though nothing has been recorded yet.
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely asked for.
   *
   * <p><b>One route is deliberately excluded, and it is the cached-read exclusion.</b> {@code POST
   * /idp/token} is this process fetching its own machine credential for qits-githost —
   * {@code client_credentials}, cached by quarkus-oidc-client for the token's whole hour, so it
   * happens <b>exactly once per run</b>. Which story pays for it is therefore a stopwatch question
   * rather than a fact about that story: whichever one first publishes to the git host draws an
   * arrow the identical story would not draw if it ran second, and the {@code networkHash} moves
   * with nothing having changed. What every story that publishes <em>does</em> carry is the git
   * host traffic itself, which is the evidence that the credential worked. The dependency is not
   * lost, only kept out of the per-story diagrams: it is stated in AGENTS.md and it is
   * {@code stubTheGitHostTokenEndpoint} above that makes it exist at all.
   */
  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .filter(request -> !TOKEN_PATH.equals(request.path()))
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }

  @UserStory(
      value = "On start, the projects service fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-projects must validate service bearers before any caller arrives:
      at startup it fetches the signing keys (JWKS) from qits-platform-idp — discovery stays
      off, the path is configured — so the very first machine request is accepted. qits-ci reads
      the repository catalogue with exactly this credential.
      """)
  @Order(1)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-projects starts with the OIDC tenant on, beside a reachable qits-platform-idp");
    given().get("/projects/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. The edge itself is drained from the mock's recording; what is asserted here is
    // that it happened, and the note is the one thing the recording cannot carry — WHEN.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .note("the signing keys were fetched at startup, before this story presented any token")
        .as("jwks-fetched");

    // End (b), the projects side: those keys are what token validation now runs on. A platform
    // service's bearer (aud = this service, roles in `groups`) opens the guarded catalogue —
    // GET /repositories is qits-ci's trigger catalogue, one of the four routes here that name
    // qits:system beside qits:admin because a sibling service and a browser both read it.
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this
    // is what makes the observed edge read `a platform service -> qits-projects`.
    NetworkCapture.actor("a platform service");
    String platformToken =
        idp.token()
            .subject("qits-ci")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + platformToken)
        .get("/projects/api/repositories")
        .then()
        .statusCode(200)
        .body("repositories", notNullValue());
    story
        .note("a platform service's bearer (aud=qits-projects, groups=[qits:system]) is accepted")
        .as("catalogue-served");
  }

  @UserStory(
      value = "A stranger's token never opens the projects catalogue",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys: a token signed by a key the published JWKS
      never carried, or minted for another service's audience, is refused at the door — however
      well-formed it looks. Both are 401 and not 403: the credential never became an identity,
      so there is no caller to have been forbidden.
      """)
  @Order(2)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    // Everything this story sends is an impostor's, so the actor is set once, up front.
    NetworkCapture.actor("an impostor");

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .get("/projects/api/repositories")
        .then()
        .statusCode(401);
    // Both refusals are the same edge — same actor, same route, same status — so the diagram
    // draws one arrow and the notes are what keep the two credentials distinguishable. That is
    // the right division: the graph says who reached what and got what, the steps say why.
    story
        .note("a token signed by a key the published JWKS never carried is refused")
        .as("unknown-key-refused");

    String wrongAudienceToken =
        idp.token().audience("some-other-service").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get("/projects/api/repositories")
        .then()
        .statusCode(401);
    story
        .note("a token minted for another service's audience is refused just the same")
        .as("wrong-audience-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete now also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    // Observed on the near side, by the filter, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY,
        ACCEPTED_SLUG,
        "http",
        "a platform service",
        SERVICE,
        "GET /projects/api/repositories -> 200");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "catalogue-served");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY,
        DENIED_SLUG,
        "http",
        "an impostor",
        SERVICE,
        "GET /projects/api/repositories -> 401");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
  }
}
