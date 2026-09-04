package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.ManifestBumpException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The bump engine, offline: no Quarkus, no database, no git host — a tree is a map and the answer is
 * a map. That is the whole reason {@link ManifestVersionBump} takes a {@link
 * ManifestVersionBump.Source} instead of reading anything itself.
 *
 * <p><b>The assertion that carries this class is the round trip:</b> replacing the new version back
 * with the old one must reproduce the original file byte for byte. A splice that is right about the
 * version and wrong about one surrounding character produces a file that still looks like a pom at a
 * glance, and only the round trip catches it.
 */
public class ManifestVersionBumpTest {

  /** A tree that is a map, in the order it was written. */
  private static ManifestVersionBump.Source tree(Map<String, String> files) {
    return new ManifestVersionBump.Source() {
      @Override
      public List<String> paths() {
        return List.copyOf(files.keySet());
      }

      @Override
      public String read(String path) {
        String content = files.get(path);
        if (content == null) {
          throw new ManifestBumpException("no-such-path: " + path);
        }
        return content;
      }
    };
  }

  private static final String ROOT_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-thing-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <packaging>pom</packaging>
        <!-- a comment that must survive the bump byte for byte -->
        <modules>
          <module>domain</module>
          <module>service</module>
        </modules>
      </project>
      """;

  private static final String MODULE_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <parent>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>qits-thing-parent</artifactId>
          <version>1.0.0-SNAPSHOT</version>
        </parent>
        <artifactId>qits-thing-domain</artifactId>
      </project>
      """;

  /** A module that declares its own version and depends on a sibling by a literal — both move. */
  private static final String VENDORED_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-thing-service</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-thing-domain</artifactId>
            <version>1.0.0-SNAPSHOT</version>
          </dependency>
          <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest</artifactId>
            <version>3.34.6</version>
          </dependency>
        </dependencies>
      </project>
      """;

  private static Map<String, String> reactor() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("pom.xml", ROOT_POM);
    files.put("domain/pom.xml", MODULE_POM);
    files.put("service/pom.xml", VENDORED_POM);
    files.put("README.md", "not a manifest");
    return files;
  }

  // ---------------------------------------------------------------------------------------------
  // Maven
  // ---------------------------------------------------------------------------------------------

  @Test
  public void aReactorIsWalkedByModuleAndEveryVersionElementMoves() {
    ManifestVersionBump.Result result =
        ManifestVersionBump.stamp(tree(reactor()), "2026.903.120000");

    assertEquals(Set.of(ManifestVersionBump.Stack.MAVEN), result.stacks());
    assertEquals(
        Set.of("pom.xml", "domain/pom.xml", "service/pom.xml"),
        result.files().keySet(),
        "every pom of the reactor and nothing else");

    assertTrue(result.files().get("pom.xml").contains("<version>2026.903.120000</version>"));
    assertTrue(
        result.files().get("domain/pom.xml").contains("<version>2026.903.120000</version>"),
        "the in-reactor <parent><version> follows the root");
    assertTrue(
        result.files().get("service/pom.xml").contains("<version>3.34.6</version>"),
        "a dependency outside the reactor is left exactly alone");
    assertEquals(
        2,
        result.files().get("service/pom.xml").split("2026\\.903\\.120000", -1).length - 1,
        "its own version and the LITERAL in-reactor dependency's, and nothing more");
  }

  @Test
  public void theBumpIsAPureSpliceAndTheRoundTripProvesIt() {
    Map<String, String> files = reactor();
    ManifestVersionBump.Result bumped = ManifestVersionBump.stamp(tree(files), "2026.903.120000");

    // Splice the old version back in through the same engine: byte-identical, comments included.
    Map<String, String> after = new LinkedHashMap<>(files);
    after.putAll(bumped.files());
    ManifestVersionBump.Result back = ManifestVersionBump.stamp(tree(after), "1.0.0");

    Map<String, String> restored = new LinkedHashMap<>(after);
    restored.putAll(back.files());
    for (String path : files.keySet()) {
      assertEquals(
          files.get(path).replace("1.0.0-SNAPSHOT", "1.0.0"),
          restored.get(path),
          path + " lost bytes the bump had no business touching");
    }
  }

  @Test
  public void anExpressionVersionIsNeverRewritten() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("pom.xml", ROOT_POM);
    files.put("domain/pom.xml", MODULE_POM);
    files.put(
        "service/pom.xml",
        VENDORED_POM.replace(
            "<artifactId>qits-thing-domain</artifactId>\n"
                + "      <version>1.0.0-SNAPSHOT</version>",
            "<artifactId>qits-thing-domain</artifactId>\n"
                + "      <version>${project.version}</version>"));

    ManifestVersionBump.Result result = ManifestVersionBump.stamp(tree(files), "2026.903.120000");
    assertTrue(
        result.files().get("service/pom.xml").contains("<version>${project.version}</version>"),
        "${…} is the mechanism that already works; rewriting it would break it");
  }

  @Test
  public void aModuleWithNoPomIsLoudRatherThanSkipped() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("pom.xml", ROOT_POM);
    files.put("domain/pom.xml", MODULE_POM);
    // service/pom.xml is simply absent.
    ManifestBumpException thrown =
        assertThrows(
            ManifestBumpException.class,
            () -> ManifestVersionBump.stamp(tree(files), "2026.903.120000"));
    assertTrue(thrown.getMessage().contains("service"), thrown.getMessage());
  }

  // ---------------------------------------------------------------------------------------------
  // npm
  // ---------------------------------------------------------------------------------------------

  private static final String PACKAGE_JSON =
      """
      {
        "name": "qits-spa-thing",
        "version": "0.0.0",
        "scripts": { "build": "ng build" }
      }
      """;

  private static final String LOCK =
      """
      {
        "name": "qits-spa-thing",
        "version": "0.0.0",
        "lockfileVersion": 3,
        "packages": {
          "": {
            "name": "qits-spa-thing",
            "version": "0.0.0"
          },
          "node_modules/tslib": {
            "version": "2.8.1",
            "resolved": "http://localhost:8081/repository/npm/tslib/-/tslib-2.8.1.tgz"
          }
        }
      }
      """;

  @Test
  public void exactlyThreeLockAndManifestFieldsMoveAndTheResolvedUrlsDoNot() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("package.json", PACKAGE_JSON);
    files.put("package-lock.json", LOCK);
    files.put("projects/thing-lib/package.json", PACKAGE_JSON.replace("0.0.0", "0.1.0"));

    ManifestVersionBump.Result result =
        ManifestVersionBump.stamp(tree(files), "2026.903.120000");

    assertEquals(Set.of(ManifestVersionBump.Stack.NPM), result.stacks());
    assertEquals(
        Set.of("package.json", "package-lock.json", "projects/thing-lib/package.json"),
        result.files().keySet(),
        "the library manifest is the PUBLISHED one for a library repository and comes along");

    String lock = result.files().get("package-lock.json");
    assertEquals(
        2,
        lock.split("2026\\.903\\.120000", -1).length - 1,
        "the lock's .version and .packages[\"\"].version, and nothing else");
    assertTrue(
        lock.contains("\"version\": \"2.8.1\""), "a dependency's pinned version is not a release");
    assertTrue(
        lock.contains("http://localhost:8081/repository/npm/tslib/-/tslib-2.8.1.tgz"),
        "the pinned resolved URL is exactly the content a regeneration would destroy");
  }

  @Test
  public void aManifestWithNoVersionIsLoud() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("package.json", "{\"name\":\"qits-spa-thing\"}");
    ManifestBumpException thrown =
        assertThrows(
            ManifestBumpException.class,
            () -> ManifestVersionBump.stamp(tree(files), "2026.903.120000"));
    assertTrue(thrown.getMessage().contains("declares no"), thrown.getMessage());
  }

  // ---------------------------------------------------------------------------------------------
  // Detection, and the version itself
  // ---------------------------------------------------------------------------------------------

  @Test
  public void aRepositoryWithNoManifestsIsAReleaseWithNothingToBump() {
    ManifestVersionBump.Result result =
        ManifestVersionBump.stamp(
            tree(Map.of("README.md", "a docs repository", "docs/index.md", "hello")),
            "2026.903.120000");
    assertEquals(Set.of(), result.stacks());
    assertEquals(Map.of(), result.files(), "empty is an answer, not a failure: the tag is the release");
  }

  @Test
  public void aNestedManifestOutsideTheReactorIsNotADetection() {
    ManifestVersionBump.Result result =
        ManifestVersionBump.stamp(
            tree(Map.of("web/package.json", "{\"version\":\"1\"}", "README.md", "x")),
            "2026.903.120000");
    assertEquals(Set.of(), result.stacks(), "detection is the ROOT of the tree and nothing else");
  }

  @Test
  public void theStampIsIntegerArithmeticInUtcAndCarriesNoLeadingZero() {
    assertEquals("2026.731.193059", VersionStamp.of(Instant.parse("2026-07-31T19:30:59Z")));
    assertEquals("2026.731.93059", VersionStamp.of(Instant.parse("2026-07-31T09:30:59Z")));
    assertEquals("2026.101.0", VersionStamp.of(Instant.parse("2026-01-01T00:00:00Z")));
    assertEquals("2026.1231.235959", VersionStamp.of(Instant.parse("2026-12-31T23:59:59Z")));
    // UTC is pinned rather than the host's zone: the same instant must render the same everywhere.
    assertEquals("2026.101.0", VersionStamp.of(Instant.parse("2025-12-31T19:00:00-05:00")));
  }

  @Test
  public void aVersionStringTheSplicesCouldNotSafelyCarryIsRefused() {
    assertThrows(
        ManifestBumpException.class,
        () -> ManifestVersionBump.stamp(tree(reactor()), "1.0 </version><evil>"));
  }
}
