package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The wrapper's {@code .gitmodules} editor. A plain JUnit test — the class is pure text in, text
 * out, which is exactly why it was worth separating from the git plumbing that carries it.
 *
 * <p>What most of these cases are really about is <b>what does not change</b>: a project's
 * configuration is a file people read and review, so every section this editor was not asked about
 * has to come back byte for byte.
 */
class WrapperGitmodulesTest {

  private static final String EXISTING =
      """
      [submodule "qits-gateway"]
      \tpath = services/qits-gateway
      \turl = ../qits-gateway.git
      \tignore = all
      \tbranch = main
      \tupdate = merge
      [submodule "qits-spa-home"]
      \tpath = frontends/qits-spa-home
      \turl = ../qits-spa-home.git
      \tignore = all
      \tbranch = main
      \tupdate = merge
      """;

  @Test
  void addingAnEntryAppendsItAndLeavesEveryOtherByteAlone() {
    String next = WrapperGitmodules.addEntry(EXISTING, "qits-cli-bootstrap", "cli");

    assertTrue(next.startsWith(EXISTING), "the existing sections are untouched, byte for byte");
    assertEquals(
        EXISTING
            + """
            [submodule "qits-cli-bootstrap"]
            \tpath = cli/qits-cli-bootstrap
            \turl = ../qits-cli-bootstrap.git
            \tbranch = main
            \tignore = all
            \tupdate = merge
            """,
        next);
  }

  @Test
  void anEmptyFileGetsTheEntryAndNothingElse() {
    assertEquals(
        """
        [submodule "shared"]
        \tpath = libs/shared
        \turl = ../shared.git
        \tbranch = main
        \tignore = all
        \tupdate = merge
        """,
        WrapperGitmodules.addEntry("", "shared", "libs"));
  }

  /** A file someone left without a trailing newline must not have the next header glued onto it. */
  @Test
  void aFileWithNoTrailingNewlineGetsOne() {
    String next = WrapperGitmodules.addEntry("[submodule \"a\"]\n\tpath = libs/a", "b", "libs");
    assertTrue(next.contains("\tpath = libs/a\n[submodule \"b\"]"), next);
  }

  @Test
  void addingTheSameEntryTwiceChangesNothing() {
    String once = WrapperGitmodules.addEntry(EXISTING, "shared", "libs");
    assertSame(once, WrapperGitmodules.addEntry(once, "shared", "libs"));
  }

  @Test
  void aNameAlreadyUsedForADifferentDirectoryIsACollision() {
    BadRequestException e =
        assertThrows(
            BadRequestException.class,
            () -> WrapperGitmodules.addEntry(EXISTING, "qits-gateway", "libs"));
    assertTrue(e.getMessage().contains("services/qits-gateway"), e.getMessage());
  }

  @Test
  void aPathAlreadyOccupiedByAnotherSectionIsACollision() {
    String taken =
        """
        [submodule "other"]
        \tpath = libs/shared
        \turl = ../elsewhere.git
        """;
    BadRequestException e =
        assertThrows(
            BadRequestException.class, () -> WrapperGitmodules.addEntry(taken, "shared", "libs"));
    assertTrue(e.getMessage().contains("cannot share a path"), e.getMessage());
  }

  @Test
  void removingAnEntryCutsExactlyItsSection() {
    String next = WrapperGitmodules.removeEntry(EXISTING, "qits-gateway");
    assertEquals(
        """
        [submodule "qits-spa-home"]
        \tpath = frontends/qits-spa-home
        \turl = ../qits-spa-home.git
        \tignore = all
        \tbranch = main
        \tupdate = merge
        """,
        next);
  }

  @Test
  void removingTheLastEntryKeepsTheTrailingNewline() {
    String next = WrapperGitmodules.removeEntry(EXISTING, "qits-spa-home");
    assertTrue(next.endsWith("update = merge\n"), "the file still ends in a newline: " + next);
    assertFalse(next.contains("qits-spa-home"));
  }

  @Test
  void removingEverythingLeavesAnEmptyFile() {
    String next = WrapperGitmodules.removeEntry(EXISTING, "qits-gateway");
    assertEquals("", WrapperGitmodules.removeEntry(next, "qits-spa-home"));
  }

  @Test
  void removingSomethingThatIsNotThereChangesNothing() {
    assertSame(EXISTING, WrapperGitmodules.removeEntry(EXISTING, "never-added"));
  }

  @Test
  void nonSubmoduleSectionsAndCommentsSurviveBothVerbs() {
    String withNoise =
        """
        # this file is the project
        [core]
        \tsomething = kept
        [submodule "a"]
        \tpath = libs/a
        \turl = ../a.git
        """;
    String added = WrapperGitmodules.addEntry(withNoise, "b", "libs");
    assertTrue(added.startsWith(withNoise), added);
    String removed = WrapperGitmodules.removeEntry(added, "a");
    assertTrue(removed.contains("# this file is the project"), removed);
    assertTrue(removed.contains("[core]"), removed);
    assertTrue(removed.contains("something = kept"), removed);
    assertTrue(removed.contains("[submodule \"b\"]"), removed);
    assertFalse(removed.contains("libs/a"), removed);
  }

  @Test
  void entriesReadsNamePathAndUrlInFileOrder() {
    List<WrapperGitmodules.Entry> entries = WrapperGitmodules.entries(EXISTING);
    assertEquals(2, entries.size());
    assertEquals(
        new WrapperGitmodules.Entry("qits-gateway", "services/qits-gateway", "../qits-gateway.git"),
        entries.get(0));
    assertEquals("frontends/qits-spa-home", entries.get(1).path());
    assertTrue(WrapperGitmodules.hasEntry(EXISTING, "qits-spa-home"));
    assertFalse(WrapperGitmodules.hasEntry(EXISTING, "nope"));
    assertEquals(List.of(), WrapperGitmodules.entries(""));
    assertEquals(List.of(), WrapperGitmodules.entries(null));
  }

  @Test
  void aNameThatWouldBreakTheSectionHeaderIsRefused() {
    assertThrows(
        BadRequestException.class, () -> WrapperGitmodules.addEntry(EXISTING, "a\"]\n[b", "libs"));
    assertThrows(BadRequestException.class, () -> WrapperGitmodules.addEntry(EXISTING, " ", "libs"));
    assertThrows(BadRequestException.class, () -> WrapperGitmodules.addEntry(EXISTING, "a", ""));
  }
}
