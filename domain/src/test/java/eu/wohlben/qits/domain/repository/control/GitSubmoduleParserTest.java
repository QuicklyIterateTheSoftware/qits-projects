package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.repository.control.GitSubmoduleParser.Submodule;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@code .gitmodules} parse + relative-url resolution. Instantiated
 * directly (no CDI) since {@code parse}/{@code resolveSubmoduleUrl} don't touch the injected {@code
 * GitExecutor}.
 */
class GitSubmoduleParserTest {

  private final GitSubmoduleParser parser = new GitSubmoduleParser();

  @Test
  void parsesSectionsWithPathUrlAndBranch() {
    String content =
        """
        [submodule "src/main/webui"]
        \tpath = src/main/webui
        \turl = ../qits-fixture-angular.git
        \tbranch = main
        [submodule "libs/shared"]
        \tpath = libs/shared
        \turl = https://github.com/wohlben/shared.git
        """;

    List<Submodule> subs = parser.parse(content);

    assertEquals(2, subs.size());
    Submodule webui = subs.get(0);
    assertEquals("src/main/webui", webui.name());
    assertEquals("src/main/webui", webui.path());
    assertEquals("../qits-fixture-angular.git", webui.url());
    assertEquals(Optional.of("main"), webui.branch());
    Submodule shared = subs.get(1);
    assertEquals("libs/shared", shared.name());
    assertEquals("https://github.com/wohlben/shared.git", shared.url());
    assertEquals(Optional.empty(), shared.branch());
  }

  @Test
  void dropsEntriesMissingPathOrUrl() {
    String content =
        """
        [submodule "no-url"]
        \tpath = some/path
        [submodule "no-path"]
        \turl = ../child.git
        [submodule "ok"]
        \tpath = ok
        \turl = ../ok.git
        """;

    List<Submodule> subs = parser.parse(content);

    assertEquals(1, subs.size());
    assertEquals("ok", subs.get(0).name());
  }

  @Test
  void dropsEntriesWithUnsafeUrl() {
    String content =
        """
        [submodule "evil-ext"]
        \tpath = evil
        \turl = ext::sh -c id
        [submodule "evil-dash"]
        \tpath = dash
        \turl = --upload-pack=touch pwned
        [submodule "ok"]
        \tpath = ok
        \turl = ../ok.git
        """;

    List<Submodule> subs = parser.parse(content);

    assertEquals(1, subs.size());
    assertEquals("ok", subs.get(0).name());
  }

  @Test
  void emptyContentYieldsNoSubmodules() {
    assertTrue(parser.parse("").isEmpty());
  }

  @Test
  void resolvesRelativeUrlAgainstHttpsSuperproject() {
    assertEquals(
        "https://github.com/wohlben/qits-fixture-angular.git",
        parser.resolveSubmoduleUrl(
            "https://github.com/wohlben/qits-fixture-quarkus-angular.git",
            "../qits-fixture-angular.git"));
  }

  @Test
  void resolvesRelativeUrlAgainstLocalBarePath() {
    assertEquals(
        "/abs/fixtures/child-a.git",
        parser.resolveSubmoduleUrl("/abs/fixtures/testing-repo.git", "../child-a.git"));
  }

  @Test
  void resolvesRelativeUrlAgainstScpSuperproject() {
    assertEquals(
        "git@github.com:wohlben/qits-fixture-angular.git",
        parser.resolveSubmoduleUrl(
            "git@github.com:wohlben/qits-fixture-quarkus-angular.git",
            "../qits-fixture-angular.git"));
  }

  @Test
  void resolvesNestedParentTraversal() {
    assertEquals(
        "https://host/a/foo.git",
        parser.resolveSubmoduleUrl("https://host/a/b/c/super.git", "../../../foo.git"));
  }

  @Test
  void resolvesDotSlashWithoutClimbing() {
    assertEquals(
        "/abs/fixtures/super.git/sub.git",
        parser.resolveSubmoduleUrl("/abs/fixtures/super.git", "./sub.git"));
  }

  @Test
  void leavesAbsoluteUrlUnchanged() {
    assertEquals(
        "https://github.com/wohlben/other.git",
        parser.resolveSubmoduleUrl(
            "/abs/fixtures/super.git", "https://github.com/wohlben/other.git"));
  }
}
