package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The single reading of a wrapper path, under both layouts. Pure, so it is tested against strings —
 * the same treatment {@link WrapperGitmodules} gets, and for the same reason: this is where the
 * project's configuration is interpreted.
 */
public class WrapperPathTest {

  @Test
  public void anArchetypeDirectoryIsReadExactlyAsItAlwaysWas() {
    WrapperPath parsed = WrapperPath.parse("services/qits-ci");

    assertFalse(parsed.isComponentLayout());
    assertEquals("services", parsed.directory());
    assertEquals("qits-ci", parsed.name());
    assertNull(parsed.component());
    assertEquals(RepositoryArchetype.SERVICE, parsed.directoryArchetype());
  }

  @Test
  public void aComponentPathNamesItsComponentAndDeclaresNoArchetype() {
    WrapperPath parsed = WrapperPath.parse("components/qits-ci/qits-ci");

    assertTrue(parsed.isComponentLayout());
    assertEquals("qits-ci", parsed.component());
    assertEquals("qits-ci", parsed.name());
    assertNull(parsed.directory());
    assertNull(
        parsed.directoryArchetype(),
        "the first segment is the marker and the second is the component, so no directory says a"
            + " kind");
  }

  /**
   * Anything that is neither shape falls back to the archetype reading, where a directory no
   * archetype claims is already skipped with a warning. Guessing what {@code components/a/b/c} means
   * would be inventing taxonomy.
   */
  @Test
  public void anythingElseIsReadAsAnArchetypePathAndClaimsNoArchetype() {
    assertNull(WrapperPath.parse("components/orphan").directoryArchetype());
    assertNull(WrapperPath.parse("components/a/b/c").directoryArchetype());
    assertNull(WrapperPath.parse("vendor/vendored").directoryArchetype());
    assertNull(WrapperPath.parse("bare-name"));
    assertNull(WrapperPath.parse("trailing/"));
    assertNull(WrapperPath.parse("  "));
    assertNull(WrapperPath.parse(null));
  }

  /** A wrapper is flipped one entry at a time, so the first entry moved decides the layout. */
  @Test
  public void oneMovedEntryIsEnoughToCallAManifestFlipped() {
    assertFalse(
        WrapperPath.usesComponentLayout(
            List.of(new WrapperGitmodules.Entry("a", "services/a", "../a.git"))));
    assertTrue(
        WrapperPath.usesComponentLayout(
            List.of(
                new WrapperGitmodules.Entry("a", "services/a", "../a.git"),
                new WrapperGitmodules.Entry("b", "components/c/b", "../b.git"))));
  }

  @Test
  public void theRoleSuffixOfANameIsTheComponentLayoutsOnlyKindDerivation() {
    assertEquals(
        RepositoryArchetype.SERVICE, RepositoryArchetype.fromRepositoryName("qits-ci-service"));
    assertEquals(
        RepositoryArchetype.SERVICE,
        RepositoryArchetype.fromRepositoryName("qits-deployments-platform-service"),
        "a tier modifier sits before the role, so the suffix still decides");
    assertEquals(
        RepositoryArchetype.DAEMON, RepositoryArchetype.fromRepositoryName("qits-workspace-daemon"));
    assertEquals(
        RepositoryArchetype.FRONTEND, RepositoryArchetype.fromRepositoryName("qits-ci-frontend"));
    assertEquals(
        RepositoryArchetype.IMAGE, RepositoryArchetype.fromRepositoryName("qits-workspace-oci"));
    assertEquals(RepositoryArchetype.CLI, RepositoryArchetype.fromRepositoryName("qits-bootstrap-cli"));
    assertEquals(
        RepositoryArchetype.LIBRARY,
        RepositoryArchetype.fromRepositoryName("qits-eventstream-javalib"));
    assertEquals(
        RepositoryArchetype.LIBRARY,
        RepositoryArchetype.fromRepositoryName("qits-ui-components-jslib"));
  }

  /** Every name the renames have not reached, which in phase 1 is all of them. */
  @Test
  public void aNameWithNoRoleSuffixDeclaresNoKind() {
    assertNull(RepositoryArchetype.fromRepositoryName("qits-ci"));
    assertNull(RepositoryArchetype.fromRepositoryName("qits-spa-ci"));
    assertNull(RepositoryArchetype.fromRepositoryName("cli"), "a bare role word is not a suffix");
    assertNull(RepositoryArchetype.fromRepositoryName(""));
    assertNull(RepositoryArchetype.fromRepositoryName(null));
  }
}
