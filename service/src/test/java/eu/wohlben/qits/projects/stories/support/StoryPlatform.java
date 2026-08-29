package eu.wohlben.qits.projects.stories.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.api.GitHostFixture;
import io.restassured.RestAssured;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The platform state a story <b>walks through</b> rather than creates — one project, one component
 * repository, and one bare the git host already serves.
 *
 * <h2>Setup is invisible to both taps, by construction</h2>
 *
 * <p>A story's diagram must show the walk somebody takes, not the fixture somebody built. So nothing
 * here touches either of the two things a story is observed through:
 *
 * <ul>
 *   <li><b>The inbound tap</b> is the framework's RestAssured filter, which is JVM-global once
 *       installed. This class therefore drives the API with a plain {@link HttpClient} — a client
 *       no filter is attached to — so not one fixture request becomes an arrow into qits-projects.
 *   <li><b>The outbound tap</b> is the git host's own access log, and the fixture's project
 *       creation genuinely does reach it. That one is bounded by <b>order</b> instead:
 *       {@link #provision()} runs before {@link StoryGitHost#install()} in every story class's
 *       {@code @BeforeAll}, and install takes the end of the recording as its floor.
 * </ul>
 *
 * <p>The seeded bare goes further and touches no wire at all: it is written straight onto the
 * fixture's disk with a plain {@code git init --bare}, which is a plane neither tap can see — and
 * which is also the truth about a platform whose repositories are created on the git host by
 * qits-cli-bootstrap long before any row here names them.
 *
 * <h2>Provisioned once, for whichever story class runs first</h2>
 *
 * <p>Every story class calls {@link #provision()}; the first one does the work and the rest find it
 * done. That is what makes each class runnable on its own ({@code -Dit.test=EpicPlanningIT}) while
 * a full run still provisions exactly once — and it is why the floor is correct in both cases,
 * since the only provisioning there ever is happens before the only install there ever is.
 *
 * <h2>The ids are minted, the names are readable</h2>
 *
 * <p>A project's slug is unique and immutable, so the fixture's carries a run stamp; nothing about
 * that reaches a diagram, because a project id and a repository id are UUIDs and {@link
 * eu.wohlben.qits.userflows.Labels} rewrites a whole UUID path segment to {@code {id}}. The seeded
 * bare's storage id is the one exception and is deliberately a <b>readable literal</b>: it is a
 * storage key the bootstrap chose, it survives scrubbing unchanged, and {@code GET
 * /git/bootstrap-seeded-repo -> 200} says which repository was adopted where {@code GET /git/{id}}
 * would say nothing at all.
 */
public final class StoryPlatform {

  /** The archetype the fixture's component takes — {@code libs/} in the wrapper. */
  public static final String COMPONENT_ARCHETYPE = "LIBRARY";

  /** The fixture component's addressable name — what {@code ../<name>.git} resolves to. */
  public static final String COMPONENT_NAME = "userflow-reference-lib";

  /**
   * The storage id of a bare this git host already serves and no row here knows about — the exact
   * state qits-cli-bootstrap leaves behind, and the only state {@code POST …/repositories/adopt}
   * has anything to do.
   */
  public static final String SEEDED_REPO_ID = "bootstrap-seeded-repo";

  /** The public coordinate the adoption registers for it. */
  public static final String SEEDED_REPO_NAME = "bootstrap-seeded-repo";

  /** The branch every bare on this host publishes. */
  public static final String DEFAULT_BRANCH = "main";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The fixture's own client — <b>not</b> RestAssured, which is where the inbound tap lives. One
   * per JVM, because a client per request would leave a connection pool behind for each.
   */
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  /** Generous: a project create pushes a whole template skeleton over the git wire protocol. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

  private static final Object LOCK = new Object();

  private static boolean provisioned;
  private static String projectId;
  private static String projectSlug;
  private static String wrapperRepositoryId;
  private static String componentRepositoryId;

  private StoryPlatform() {}

  /** Build the fixture, once per JVM. Safe to call from every story class's {@code @BeforeAll}. */
  public static void provision() {
    synchronized (LOCK) {
      if (provisioned) {
        return;
      }
      // Run-unique and deliberately SHORT: the wrapper is named <slug>-<slug> and a repository name
      // is one path segment on the git host, capped at 64 characters — a full nanosecond stamp on
      // both halves would be refused with a message about the name rather than about the stamp.
      long stamp = System.nanoTime() % 100_000_000L;
      projectSlug = "userflow-ref-" + stamp;
      JsonNode created =
          post(
              StoryTarget.PROJECTS_PATH,
              """
              {"name":"Userflow Reference %d","slug":"%s",
               "description":"The project every qits-projects userflow walks through.",
               "dns":{"domain":"userflow-reference.test.eu","type":"A","value":"203.0.113.7"}}
              """
                  .formatted(stamp, projectSlug));
      projectId = created.path("project").path("id").asText(null);
      wrapperRepositoryId = created.path("wrapper").path("id").asText(null);
      require(projectId, "the fixture project's id");
      require(wrapperRepositoryId, "the fixture project's wrapper repository id");

      JsonNode component =
          post(
              StoryTarget.projectRepositoriesPath(projectId),
              """
              {"name":"%s","archetype":"%s"}
              """
                  .formatted(COMPONENT_NAME, COMPONENT_ARCHETYPE));
      componentRepositoryId = component.path("repository").path("id").asText(null);
      require(componentRepositoryId, "the fixture component repository's id");

      seedBareOnTheGitHost(SEEDED_REPO_ID);
      provisioned = true;
    }
  }

  /** The project every story that needs one already-existing project walks through. */
  public static String projectId() {
    return required(projectId, "projectId");
  }

  /** Its slug — the public spelling of a clone url, and what its wrapper is named after. */
  public static String projectSlug() {
    return required(projectSlug, "projectSlug");
  }

  /** Its wrapper repository: the project's own configuration, as a repository. */
  public static String wrapperRepositoryId() {
    return required(wrapperRepositoryId, "wrapperRepositoryId");
  }

  /** Its one component — what a task binds to, and what the wrapper's manifest names. */
  public static String componentRepositoryId() {
    return required(componentRepositoryId, "componentRepositoryId");
  }

  /**
   * Wait out the mirror <b>freshness window</b>, so the next read that consults a wrapper's
   * {@code .gitmodules} really fetches rather than trusting what it already has.
   *
   * <p>{@code RepoMirror.refresh()} is throttled: a mirror fetched less than {@code
   * qits.projects.git.mirror-freshness-ms} (5s, shipped in {@code domain}'s own defaults) ago is
   * trusted as it stands. That is the right behaviour — the window bounds UI reads and nothing that
   * <i>decides</i> anything reads through it — but it makes "this read reached the git host" a
   * stopwatch question rather than a fact about the read: the identical story draws the arrow when
   * it runs six seconds after its neighbour and does not when it runs four, and the {@code
   * networkHash} moves with nothing having changed.
   *
   * <p>So a story that wants to document the fetch waits for the window to lapse first, exactly as
   * qits-ci's stories wait out that service's repository-listing cache. The margin is generous
   * because the window starts at whatever last touched the mirror, which this class cannot see.
   */
  public static void awaitMirrorFreshnessLapse() {
    try {
      Thread.sleep(7_000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  // --- the bare nobody registered ----------------------------------------------------------------

  /**
   * Put a bare repository on the git host's disk, the way the platform bootstrap does and with no
   * request to anything: delete-then-create, so a second run finds known state rather than a
   * repository some earlier run already adopted.
   *
   * <p>{@code git init --bare -b main} is exactly what the fixture's own lifecycle {@code PUT}
   * runs, minus the two {@code receive-pack} settings — nothing pushes to this one, because
   * adoption deliberately clones nothing and pushes nothing.
   */
  private static void seedBareOnTheGitHost(String repoId) {
    Path bare = GitHostFixture.ROOT.resolve(repoId + ".git");
    try {
      deleteRecursively(bare);
      Files.createDirectories(bare.getParent());
      git(bare.getParent(), "init", "--bare", "-q", "-b", DEFAULT_BRANCH, bare.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("could not seed " + bare, e);
    }
  }

  private static void git(Path cwd, String... args) {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.directory(cwd.toFile());
    try {
      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException(
            "git " + String.join(" ", args) + " failed:\n" + output);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted running git " + String.join(" ", args));
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    Files.walkFileTree(
        root,
        new java.nio.file.SimpleFileVisitor<Path>() {
          @Override
          public java.nio.file.FileVisitResult visitFile(
              Path p, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
            Files.deleteIfExists(p);
            return java.nio.file.FileVisitResult.CONTINUE;
          }

          @Override
          public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException failed)
              throws IOException {
            Files.deleteIfExists(d);
            return java.nio.file.FileVisitResult.CONTINUE;
          }
        });
  }

  // --- the tap-invisible client ------------------------------------------------------------------

  /**
   * One {@code POST} as an admin session, through a client no tap is attached to.
   *
   * <p>The identity is the same pair the edge asserts ({@link StoryIdentities}), because the fixture
   * has to go through the mechanism like everybody else — a launched artifact has no dev user to
   * fall back on, so a fixture that sent no headers would be refused before it created anything.
   */
  private static JsonNode post(String path, String body) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header(StoryIdentities.USER_HEADER, "the-userflow-fixture")
            .header(StoryIdentities.ROLES_HEADER, StoryIdentities.HUMAN_ROLE)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response;
    try {
      response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("fixture POST " + path + " failed", e);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fixture POST " + path + " was interrupted");
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "fixture POST " + path + " answered " + response.statusCode() + ": " + response.body());
    }
    try {
      return MAPPER.readTree(response.body());
    } catch (IOException e) {
      throw new UncheckedIOException("fixture POST " + path + " answered unparseable JSON", e);
    }
  }

  private static void require(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("the fixture never learned " + what);
    }
  }

  private static String required(String value, String what) {
    if (value == null) {
      throw new IllegalStateException(
          "StoryPlatform." + what + " is unset — call provision() from the story class's @BeforeAll");
    }
    return value;
  }
}
