package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.testdb.EmbeddedPg;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The surface of the <em>packaged artifact</em> — the fast-jar normally, the GraalVM binary under
 * {@code -Dnative} — because that is the only place a whole class of failure is visible.
 *
 * <p>Every other test here is a {@code @QuarkusTest}: it augments and runs in the build JVM, with
 * the full classpath present and reflection unrestricted. A native image has none of those. Three
 * real defects in this repo were invisible to the entire suite and fatal to the binary, and all
 * three are covered below:
 *
 * <ul>
 *   <li>a shipped datasource default that only the packaged process ever meets — historically
 *       {@code AUTO_SERVER=TRUE} on the H2 urls, which asked for {@code org.h2.server.TcpServer}, a
 *       class no native image has, and killed the process in connection-pool warm-up before it
 *       bound a port. The urls are {@code ${QITS_RESOURCE_*_URL}} expressions now, with no feature
 *       left in them to lose; what is under test is that the indirection itself resolves, so
 *       <em>any</em> assertion against a running server catches a jar that spells a variable
 *       wrong.
 *   <li>{@code project-template/} not being in the image, so project creation — the first thing
 *       anyone does with this service — failed with "missing from the classpath". {@code
 *       repository-template/} is the second such tree and is covered the same way, by creating a
 *       blank repository.
 *   <li>{@code RepositoryMetadata} unregistered for reflection, so startup discovery could not read
 *       a sidecar it had itself written.
 * </ul>
 *
 * <p>{@code src/test/resources/application.properties} points the launched process at {@code
 * target/} through {@code quarkus.test.arg-line}; without that this test would write into the
 * developer's real {@code ~/.qits}. The two databases cannot travel that way — their urls name a
 * port chosen at run time — so {@link PackagedResources} hands them over instead.
 *
 * <p>ITs are skipped by default ({@code skipITs} in the root pom) and the {@code native} profile
 * flips that, so {@code ./mvnw verify -Dnative} is what runs this. {@code -DskipITs=false} runs it
 * against the fast-jar.
 *
 * <p>{@code creatingAProjectSeedsTheWrapperFromTheTemplate} and {@code
 * theRemoteLoginSocketRunsARealTerminal} both publish to the git host — {@code
 * GitHostRepositories.ensure} plus a real push — and the packaged binary wires the shipped {@code
 * HttpGitHostRepositories}/{@code ConfiguredGitHostAddress}, not the {@code Fake*} CDI doubles
 * {@code domain}'s and this module's own {@code @QuarkusTest}s use (those win only inside the JVM
 * that starts them, and this test starts a separate process). {@link GitHostFixture} is that
 * process' stand-in for qits-githost: a real HTTP server in this JVM, handed to the launched
 * process as {@code qits.githost.url}.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(GitHostFixture.class)
@TestProfile(PackagedSurfaceIT.PackagedResources.class)
public class PackagedSurfaceIT {

  /**
   * Hands the launched artifact its three databases the way a deployment does — as the generic
   * resource triples {@code QITS_RESOURCE_DB_*}, {@code QITS_RESOURCE_EPICS_*} and {@code
   * QITS_RESOURCE_EVENTSTREAM_*}, not as the {@code quarkus.datasource.*} keys. The domain, epics
   * and qits-eventstream jars ship {@code jdbc.url=${QITS_RESOURCE_DB_URL}} and its two siblings, so
   * supplying the variables leaves the <b>shipped</b> expressions themselves under test. These
   * overrides reach the launched process as system properties, and expression expansion reads the
   * whole config, so the same nine names resolve there.
   *
   * <p>The third triple is not optional and is the whole reason this javadoc changed: the bus jar is
   * dark outside a deployment, and dark stops publishing and dialling rather than connecting — so a
   * packaged process handed no eventstream url dies at Flyway naming the missing variable, which is
   * exactly the refuse-to-boot stance this IT exists to keep honest.
   *
   * <p>The databases are on an embedded postgres this JVM starts. <b>Their urls travel through
   * system properties rather than static fields</b>: a test profile is instantiated in more than
   * one classloader, so a field written by one copy is not the field the other reads, while the
   * process has exactly one property table.
   */
  public static class PackagedResources implements QuarkusTestProfile {

    /** Where each url is parked for whichever copy of this class is asked second. */
    private static final String URL_PROPERTY_PREFIX = "qits.test.packaged-it.db-url.";

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl("qp_it_projects"),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD,
          "QITS_RESOURCE_EPICS_URL", databaseUrl("qp_it_epics"),
          "QITS_RESOURCE_EPICS_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_EPICS_PASSWORD", EmbeddedPg.PASSWORD,
          "QITS_RESOURCE_EVENTSTREAM_URL", databaseUrl("qp_it_eventstream"),
          "QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);
    }

    private static synchronized String databaseUrl(String database) {
      String recorded = System.getProperty(URL_PROPERTY_PREFIX + database);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url(database);
      System.setProperty(URL_PROPERTY_PREFIX + database, url);
      return url;
    }
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheSegment() {
    given().when().get("/projects/q/openapi").then().statusCode(200);
    given().when().get("/projects/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  public void realRoutesAnswerAndOnlyUnderTheSegment() {
    given().when().get("/projects/api/projects").then().statusCode(200);
    given().when().get("/projects/api/repositories/no-such-repository").then().statusCode(404);
    // qits-gateway routes verbatim by prefix, so there is no unprefixed form to fall back to. If
    // this ever answers, quarkus.rest.path has stopped being applied.
    given().when().get("/api/projects").then().statusCode(404);
  }

  @Test
  public void theMcpServerIsMountedUnderTheSegmentAndAnswersStatelessDiscovery() {
    // Reaching the SERVER, not the router's 404 page: quarkus-mcp-server-http refuses to start at
    // all without its root-path, so a wrong root-path would show up here as a 404 with an HTML body
    // rather than a JSON-RPC result.
    given()
        .contentType("application/json")
        .accept("application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", "server/discover")
        .body(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"packaged-surface-it\","
                + "\"version\":\"1\"},\"io.modelcontextprotocol/clientCapabilities\":{}}}}")
        .when()
        .post("/projects/mcp")
        .then()
        .statusCode(200)
        .body("result.supportedVersions", org.hamcrest.Matchers.hasItem("2026-07-28"));
  }

  @Test
  public void creatingAProjectSeedsTheWrapperFromTheTemplate() {
    // The project template is a directory tree read off the classpath, which a native image does
    // not carry unless told to. Creating a project is what reads it.
    String slug = "packaged-" + System.nanoTime();
    var response =
        given()
            .contentType("application/json")
            // The dns object is required, and posting it here is the point of doing so against the
            // BINARY: the new column, the @Embedded read back and the enum's STRING mapping all
            // have
            // to survive an image that carries no reflection it was not told about.
            .body(
                "{\"name\":\"Packaged Surface\",\"slug\":\""
                    + slug
                    + "\",\"dns\":{\"domain\":\"packaged.test.eu\",\"type\":\"A\","
                    + "\"value\":\"203.0.113.9\"}}")
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract();
    // Creation only returns a wrapper once the skeleton has been committed onto its `main`; a
    // missing template throws out of the same call, so a wrapper id here is the template having
    // been read.
    assertEquals(slug, response.path("project.slug"));
    assertNotNull(response.path("wrapper.id"), "creation returned no wrapper repository");
    // Written to a real postgres by V1's dns_* columns and read back through the embeddable — the
    // one assertion here that the migration and the @Embedded mapping work outside a test JVM.
    assertEquals("packaged.test.eu", response.path("project.dns.domain"));
    assertEquals("A", response.path("project.dns.type"));
    assertEquals("203.0.113.9", response.path("project.dns.value"));

    // The second classpath tree, read the only way anything reads it: creating a blank component.
    // A wrapper path back means the skeleton was read, the repository was published to the git host
    // and the wrapper commit landed — the whole create flow, in the packaged process.
    given()
        .contentType("application/json")
        .body("{\"name\":\"blank-component\",\"archetype\":\"LIBRARY\"}")
        .when()
        .post("/projects/api/projects/" + response.path("project.id") + "/repositories")
        .then()
        .statusCode(200)
        .body("repository.name", org.hamcrest.Matchers.equalTo("blank-component"))
        .body("wrapperPath", org.hamcrest.Matchers.equalTo("libs/blank-component"));
  }

  /**
   * The sign-in terminal, end to end against the packaged artifact: the upgrade, a spawned session,
   * and its first bytes.
   *
   * <p>This is the test the PTY rewrite exists for. {@code ForeignPty} reaches libc through {@code
   * java.lang.foreign}, whose downcall stubs a native image only has because {@code
   * reachability-metadata.json} declares them and {@code ForeignPty} is initialised at run time —
   * two pieces of configuration that nothing in a JVM run consults. Receiving the banner proves the
   * socket upgraded, the registry spawned a session, the terminal was allocated, git was launched
   * onto its slave device and the reader thread is streaming it back.
   *
   * <p>Linux-only, like {@code ForeignPty}.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  public void theRemoteLoginSocketRunsARealTerminal() throws Exception {
    String origin = GitFixtures.path("testing-repo.git");
    String slug = "signin-" + System.nanoTime();
    String projectId =
        given()
            .contentType("application/json")
            .body(
                "{\"name\":\"Sign In Surface\",\"slug\":\""
                    + slug
                    + "\",\"dns\":{\"domain\":\"signin.test.eu\",\"type\":\"A\","
                    + "\"value\":\"203.0.113.9\"}}")
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    String repoId =
        given()
            .contentType("application/json")
            .body("{\"url\":\"" + origin + "\",\"archetype\":\"SERVICE\"}")
            .when()
            .post("/projects/api/projects/" + projectId + "/repositories")
            .then()
            .statusCode(200)
            .extract()
            .path("repository.id");

    Frames frames = new Frames();
    WebSocket socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
                URI.create(
                    "ws://localhost:"
                        + RestAssured.port
                        + "/projects/api/repositories/"
                        + repoId
                        + "/remote-login"),
                frames)
            .get(30, TimeUnit.SECONDS);
    try {
      assertTrue(frames.received.await(30, TimeUnit.SECONDS), "the socket sent nothing");
      String text = String.join("", frames.text);
      // The banner is seeded before the reader starts, so it is the first thing any client sees —
      // and it is only written on the path that actually allocated a terminal and spawned git.
      assertTrue(text.contains("Signing in to"), "expected the sign-in banner, got: " + text);
    } finally {
      socket.abort();
    }
  }

  /** Collects text frames and counts down as soon as anything arrives. */
  private static final class Frames implements WebSocket.Listener {
    final List<String> text = new CopyOnWriteArrayList<>();
    final CountDownLatch received = new CountDownLatch(1);

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      text.add(data.toString());
      received.countDown();
      webSocket.request(1);
      return null;
    }
  }
}
