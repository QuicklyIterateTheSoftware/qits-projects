package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b> — like {@link PackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove: the shipped tenant is
 * gated behind {@code qits.auth.machine.required} and every suite in this repository leaves that
 * gate shut, so the {@code quarkus.oidc.*} block this service actually deploys with
 * (auth-server-url + jwks-path against a real listener, audience enforcement, the {@code groups}
 * claim becoming roles) is exercised nowhere else. The far side is {@link MockIdp}, whose
 * recordings make the interaction assertable on <b>both ends</b>.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code target/userstories/} with the interactions drawn as a sequence diagram. The story is
 * browserless (no {@code Flow} parameter), so no Chromium is involved anywhere.
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
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG =
      "on-start-the-projects-service-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-projects-catalogue";

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
      return overrides;
    }
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
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-projects starts with the OIDC tenant on, beside a reachable qits-platform-idp");
    given().get("/projects/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story.happened("qits-projects", "qits-platform-idp", "GET /idp/jwks (at startup)")
        .as("jwks-fetched");

    // End (b), the projects side: those keys are what token validation now runs on. A platform
    // service's bearer (aud = this service, roles in `groups`) opens the guarded catalogue —
    // GET /repositories is qits-ci's trigger catalogue, one of the four routes here that name
    // qits:system beside qits:admin because a sibling service and a browser both read it.
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
        .happened(
            "a platform service",
            "qits-projects",
            "GET /projects/api/repositories (Bearer, groups=[qits:system])")
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
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

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
    story
        .happened(
            "an impostor",
            "qits-projects",
            "GET /projects/api/repositories (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    String wrongAudienceToken =
        idp.token().audience("some-other-service").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get("/projects/api/repositories")
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-projects",
            "GET /projects/api/repositories (another service's audience) -> 401")
        .as("wrong-audience-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        ACCEPTED_SLUG,
        "qits-projects",
        "qits-platform-idp",
        "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
  }
}
