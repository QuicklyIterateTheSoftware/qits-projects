package eu.wohlben.qits.domain.repository.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the archetype taxonomy and the project template skeleton from drifting apart: directory
 * <em>is</em> archetype, in both directions, so every placeable archetype must have a directory in
 * the template and every template directory must map back to an archetype.
 *
 * <p>A plain JUnit test — no Quarkus — since it only reads the enum and the built resources.
 */
public class RepositoryArchetypeTemplateSyncTest {

  /** The template as the build copied it, which is what actually ships. */
  private static final Path TEMPLATE = Path.of("target/classes/project-template");

  private static Set<String> templateDirectories() throws Exception {
    try (Stream<Path> entries = Files.list(TEMPLATE)) {
      return entries
          .filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  @Test
  public void everyPlaceableArchetypeHasATemplateDirectoryAndViceVersa() throws Exception {
    assertTrue(
        Files.isDirectory(TEMPLATE), "the project template is missing from the build output");

    assertEquals(
        new TreeSet<>(RepositoryArchetype.skeletonDirectories()),
        templateDirectories(),
        "the skeleton directories and the placeable archetypes must match exactly — adding an"
            + " archetype with a directory() means adding that directory to the template");
  }

  /**
   * Git cannot commit an empty directory, so each one needs a placeholder anyway; a README makes
   * the skeleton teach the convention instead of merely reserving the path.
   */
  @Test
  public void everyTemplateDirectoryCarriesAReadme() throws Exception {
    for (String directory : templateDirectories()) {
      assertTrue(
          Files.isRegularFile(TEMPLATE.resolve(directory).resolve("README.md")),
          directory + "/ needs a README.md, or git cannot commit it");
    }
  }

  @Test
  public void theUnplaceableArchetypesHaveNoDirectory() {
    for (RepositoryArchetype archetype :
        Set.of(
            RepositoryArchetype.PROJECT,
            RepositoryArchetype.SERVICE_TEMPLATE,
            RepositoryArchetype.FORK)) {
      assertEquals(null, archetype.directory(), archetype + " must not be placeable");
    }
  }

  /**
   * The trap this guards: plexus' archiver default-excludes, which maven-jar-plugin applies, drop
   * {@code .gitignore} and {@code .gitattributes} from every jar. A template resource named with a
   * leading dot therefore reaches {@code target/classes} — so tests and dev mode see it — and
   * silently vanishes from the packaged artifact, producing wrappers missing a file with no error
   * anywhere. Every dotfile is stored {@code dot-}-prefixed and un-prefixed at commit time.
   */
  @Test
  public void noTemplateResourceIsStoredWithALeadingDot() throws Exception {
    try (Stream<Path> all = Files.walk(TEMPLATE)) {
      List<String> dotted =
          all.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".")).sorted().toList();
      assertTrue(
          dotted.isEmpty(),
          "store these dot-prefixed instead, or they will not survive jar packaging: " + dotted);
    }
  }

  @Test
  public void theAgentContractSlotIsReservedWithItsSymlink() throws Exception {
    assertTrue(
        Files.isRegularFile(TEMPLATE.resolve("AGENTS.md")),
        "the agent-contract slot exists from the start, so a later step fills a path that is"
            + " already in every wrapper");
    Path symlink = TEMPLATE.resolve("CLAUDE.md.symlink");
    assertTrue(Files.isRegularFile(symlink), "CLAUDE.md is declared, not a real symlink resource");
    assertEquals(
        "AGENTS.md",
        Files.readString(symlink).strip(),
        "the declared link target is what gets committed as a 120000 blob");
  }

  @Test
  public void theStarterConfigAndGitignoreArePresent() {
    assertTrue(Files.isRegularFile(TEMPLATE.resolve("dot-qits-config.yml")));
    assertTrue(Files.isRegularFile(TEMPLATE.resolve("dot-gitignore")));
  }
}
