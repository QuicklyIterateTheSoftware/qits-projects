package eu.wohlben.qits.projects.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the archetype taxonomy and the project template skeleton from drifting apart. What they
 * have to agree about changed with the component layout, and both halves are here:
 *
 * <ul>
 *   <li><b>The template seeds one directory, {@code components/}</b>, and none of the six archetype
 *       directories. Seeding a layout the platform has flipped away from would teach every new
 *       project the wrong grammar on its first clone.
 *   <li><b>The NAME is what says the kind now</b>, so the two-way sync moved with it: every role
 *       suffix {@link RepositoryArchetype#fromRepositoryName} reads is taught by {@code
 *       components/README.md}, and every suffix that README teaches derives back to an archetype.
 * </ul>
 *
 * <p>{@link RepositoryArchetype#fromDirectory} and {@link RepositoryArchetype#placeableDirectories}
 * are unchanged and still asserted here: legacy wrappers still mount entries under the six
 * directories and the reconcile still has to read them. What is gone is the claim that the template
 * contains them.
 *
 * <p>A plain JUnit test — no Quarkus — since it only reads the enum and the built resources.
 */
public class RepositoryArchetypeTemplateSyncTest {

  /** The template as the build copied it, which is what actually ships. */
  private static final Path TEMPLATE = Path.of("target/classes/project-template");

  /** The one directory the skeleton seeds; {@code WrapperPath.COMPONENTS_DIRECTORY} is its reader. */
  private static final String COMPONENTS = "components";

  private static Set<String> templateDirectories() throws Exception {
    try (Stream<Path> entries = Files.list(TEMPLATE)) {
      return entries
          .filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  @Test
  public void theSkeletonSeedsComponentsAndNothingElse() throws Exception {
    assertTrue(
        Files.isDirectory(TEMPLATE), "the project template is missing from the build output");

    assertEquals(
        Set.of(COMPONENTS),
        templateDirectories(),
        "the wrapper skeleton is the component layout now: one components/ directory, and the six"
            + " archetype directories deliberately absent");
  }

  /**
   * Stated as its own assertion rather than left implicit in the one above, because this is the
   * regression that would be invisible: a template that seeded {@code services/} again would look
   * harmless and would teach every new project a layout the platform left behind.
   */
  @Test
  public void noArchetypeDirectoryIsSeededAnyMore() throws Exception {
    Set<String> seeded = templateDirectories();
    for (String directory : RepositoryArchetype.placeableDirectories()) {
      assertFalse(
          seeded.contains(directory),
          directory + "/ belongs to the archetype layout and must not be seeded");
    }
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
   * Directory → archetype is still the reconcile's derivation for a wrapper that predates the flip,
   * and it is still the exact inverse. This is what the deleted template directories must NOT take
   * with them.
   */
  @Test
  public void everyArchetypeDirectoryStillDerivesBackToItsArchetype() {
    for (String directory : RepositoryArchetype.placeableDirectories()) {
      RepositoryArchetype derived = RepositoryArchetype.fromDirectory(directory);
      assertEquals(
          directory, derived == null ? null : derived.directory(), directory + " must round-trip");
    }
    assertEquals(
        new TreeSet<>(Set.of("services", "daemons", "libs", "frontends", "cli", "images")),
        new TreeSet<>(RepositoryArchetype.placeableDirectories()),
        "these six are what a legacy wrapper mounts entries under; the set is closed");
    assertEquals(null, RepositoryArchetype.fromDirectory("nope"));
    assertEquals(null, RepositoryArchetype.fromDirectory(COMPONENTS));
    assertEquals(null, RepositoryArchetype.fromDirectory(null));
  }

  // --- the name is the kind: the two-way sync that replaced directory <-> archetype ---

  /** ``-service`` in the README's table, one per row. */
  private static final Pattern SUFFIX_CELL = Pattern.compile("`(-[a-z]+)`");

  private static Set<String> suffixesTheReadmeTeaches() throws Exception {
    String readme = Files.readString(TEMPLATE.resolve(COMPONENTS).resolve("README.md"));
    Matcher matcher = SUFFIX_CELL.matcher(readme);
    Set<String> found = new TreeSet<>();
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  @Test
  public void theTemplateTeachesExactlyTheRoleSuffixesTheEnumReads() throws Exception {
    assertEquals(
        new TreeSet<>(RepositoryArchetype.roleSuffixes()),
        suffixesTheReadmeTeaches(),
        "components/README.md is what tells a person which names qits can read the kind out of, so"
            + " a suffix in one and not the other is a promise nothing keeps");
  }

  @Test
  public void everySuffixTheTemplateTeachesDerivesAPlaceableArchetype() throws Exception {
    for (String suffix : suffixesTheReadmeTeaches()) {
      RepositoryArchetype derived = RepositoryArchetype.fromRepositoryName("payments" + suffix);
      assertTrue(derived != null && derived.isPlaceable(), suffix + " must name a placeable kind");
    }
    // A name that is only the suffix declares nothing — there is no component left in it.
    assertEquals(null, RepositoryArchetype.fromRepositoryName("-service"));
    assertEquals(null, RepositoryArchetype.fromRepositoryName("qits-ci"));
    assertEquals(null, RepositoryArchetype.fromRepositoryName(null));
  }

  /**
   * The taxonomy is these nine and no more. INTEGRATION and APPLICATION rode through release A as
   * deprecated aliases so Hibernate could read pre-rework rows; V4 retired those rows and dropped
   * them, and this is what stops one being reintroduced without a migration to widen the check
   * constraint for it.
   */
  @Test
  public void theTaxonomyIsExactlyTheNineValuesTheCheckConstraintAllows() {
    assertEquals(
        List.of(
            "PROJECT",
            "SERVICE",
            "DAEMON",
            "LIBRARY",
            "FRONTEND",
            "CLI",
            "IMAGE",
            "SERVICE_TEMPLATE",
            "FORK"),
        Stream.of(RepositoryArchetype.values()).map(Enum::name).toList());
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

  /** The starter config's examples must show the layout the skeleton actually seeds. */
  @Test
  public void theStarterConfigsExamplePathsAreComponentPaths() throws Exception {
    String config = Files.readString(TEMPLATE.resolve("dot-qits-config.yml"));
    assertTrue(config.contains("components/payments/payments-service"));
    for (String directory : RepositoryArchetype.placeableDirectories()) {
      assertFalse(
          config.contains(" " + directory + "/"),
          "the example paths still name the archetype directory " + directory + "/");
    }
  }
}
