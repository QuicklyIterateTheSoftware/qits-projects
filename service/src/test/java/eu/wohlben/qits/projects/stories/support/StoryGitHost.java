package eu.wohlben.qits.projects.stories.support;

import eu.wohlben.qits.projects.api.GitHostFixture;
import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The <b>outgoing</b> tap for the git host: what the launched qits-projects put there, pushed to and
 * read back while a story was running.
 *
 * <p>Nothing in this JVM is on that path — the caller is a packaged process on the far side of a
 * socket — so the only place the traffic exists is the far side's own record of it. {@link
 * GitHostFixture#requestLog()} is that record, appended to per answered request as {@code METHOD URI
 * STATUS}, and this class turns it into {@link NetworkCapture} edges.
 *
 * <h2>Why a file, and why a floor</h2>
 *
 * <p>The fixture is started by the test-resource lifecycle and read by a story method, and those
 * need not share a classloader — a static list written by one is not the list the other reads. A
 * file is a path both resolve identically, and it survives the surefire→failsafe boundary, which is
 * exactly why {@link #install()} takes a <b>floor</b>: every line already present belongs to an
 * earlier build, to the {@code @QuarkusTest} suites, or to {@link StoryPlatform}'s fixture — and
 * none of it is a story's. That last one is the reason the order in a story class's
 * {@code @BeforeAll} is load-bearing: <b>provision first, install second</b>, so the walk somebody
 * takes is in the diagram and the scaffolding somebody built is not.
 *
 * <p>The supplier is <b>cumulative and prefix-stable</b>, which is what the framework's per-source
 * cursor requires: it returns every edge harvested so far, in arrival order, and a line it decided
 * to skip is never in the list at all — so skipping can never shift an earlier story's slice, while
 * moving the floor would.
 *
 * <h2>Attribution</h2>
 *
 * <p>Every edge here is {@code qits-projects -> qits-githost}: the initiator is the service under
 * test, not the person who caused it, because direction is always <i>who dialled</i>. There is
 * therefore no actor to stamp and no hand-over to get wrong — which is the whole difference between
 * this tap and the inbound one the framework ships.
 */
public final class StoryGitHost {

  /** How a diagram names the service this fixture impersonates. */
  public static final String SERVICE_NAME = "qits-githost";

  /** One registration per JVM; re-registering under this id would keep the cursor anyway. */
  private static final String SOURCE_ID = "git-host-fixture";

  /** How long {@link #awaitRead} waits for a line to reach disk. A ceiling, not a budget. */
  private static final Duration FLUSH_PATIENCE = Duration.ofSeconds(10);

  private static final long POLL_MILLIS = 25;

  private static final Object LOCK = new Object();

  private static boolean registered;

  /** How many lines the file already held when the first story class installed the tap. */
  private static int floor;

  /** How many lines have already been turned into edges — the harvest cursor. */
  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryGitHost() {}

  /**
   * Register the tap once per JVM, taking the current end of the recording as the floor. Called
   * from every story class's {@code @BeforeAll} <b>after</b> {@link StoryPlatform#provision()}; the
   * first one to run is what bounds what any story can see.
   */
  public static void install() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      floor = allLines().size();
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, StoryGitHost::edges);
      registered = true;
    }
  }

  /**
   * Wait, briefly and without asserting anything, for a line containing {@code fragment}.
   *
   * <p>A push or a mirror refresh the launched process makes is finished before it answers, so this
   * is a guard against the last few bytes rather than against a race in the service — but a line
   * that lands after the framework's drain is a line in the <em>next</em> story's diagram, and that
   * is a defect a reader would never place. Deliberately silent on timeout: the proof is the
   * {@code assertEdge} in {@code @AfterAll}, which names the missing edge, and a failure here would
   * only obscure it.
   */
  public static void awaitRead(String fragment) {
    long deadline = System.nanoTime() + FLUSH_PATIENCE.toNanos();
    while (true) {
      for (String line : readLines()) {
        if (line.contains(fragment)) {
          return;
        }
      }
      if (System.nanoTime() >= deadline) {
        return;
      }
      sleep();
    }
  }

  /** The label an answered request renders as, once scrubbed — what an assertion has to spell. */
  public static String label(String method, String uri, int status) {
    return Labels.scrub(method + " " + uri + " -> " + status);
  }

  /** {@code /git/<repoId>} — the lifecycle route: {@code PUT} creates, {@code GET} reads. */
  public static String repoPath(String repoId) {
    return "/git/" + repoId;
  }

  /** What a {@code git fetch} asks for first — the read half of the smart-HTTP protocol. */
  public static String fetchAdvertisementPath(String repoId) {
    return repoPath(repoId) + "/info/refs?service=git-upload-pack";
  }

  /** …and its write half, which a push asks for before sending a pack. */
  public static String pushAdvertisementPath(String repoId) {
    return repoPath(repoId) + "/info/refs?service=git-receive-pack";
  }

  /** The pack itself: the one request that actually moves a ref on this host. */
  public static String receivePackPath(String repoId) {
    return repoPath(repoId) + "/git-receive-pack";
  }

  // --- the source --------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = readLines();
    if (harvested > lines.size()) {
      // The file was truncated under us (a `clean` mid-run). Start over rather than mis-slice.
      harvested = 0;
      floor = 0;
      lines = readLines();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  /**
   * One recorded line as an edge, or nothing when the line is not a request at all.
   *
   * <p><b>Nothing is excluded on merit here</b>, unlike qits-ci's namesake — which drops a cached
   * repository listing because whether a story pays for it is a stopwatch question. This service
   * has one such read too, the wrapper mirror's fetch behind {@code
   * qits.projects.git.mirror-freshness-ms}, and it is handled at the <b>other</b> end instead: the
   * story that documents it waits the window out first ({@link
   * StoryPlatform#awaitMirrorFreshnessLapse()}), which turns a timing accident back into a fact
   * about the route rather than hiding it. Every line here is therefore a request some story
   * caused.
   */
  private static Optional<NetworkEdge> edge(String line) {
    // "METHOD URI STATUS" — three fields, no quoting, and a URI can carry no raw space.
    String[] fields = line.strip().split(" ");
    if (fields.length != 3 || !fields[1].startsWith("/")) {
      return Optional.empty();
    }
    return Optional.of(
        NetworkEdge.http(
            StoryTarget.SERVICE, SERVICE_NAME, fields[0] + " " + fields[1] + " -> " + fields[2]));
  }

  /** Everything recorded since the floor — i.e. everything a story could own. */
  private static List<String> readLines() {
    List<String> all = allLines();
    return floor >= all.size() ? List.of() : all.subList(floor, all.size());
  }

  /**
   * The recording's complete lines. A missing file is an empty recording rather than a failure, and
   * an <b>unterminated tail is dropped</b>: the fixture appends while this reads, and half a line
   * would shape half an edge. The next harvest sees it whole.
   */
  private static List<String> allLines() {
    Path file = GitHostFixture.requestLog();
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL_MILLIS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
