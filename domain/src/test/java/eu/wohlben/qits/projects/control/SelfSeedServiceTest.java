package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import eu.wohlben.qits.projects.testsupport.RecordingProjectDomainRegistrar;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The startup self-seed, offline against committed fixtures — no docker, no GitHub.
 *
 * <p>What this proves is the simplification the wrapper model bought. The seed used to carry a
 * hand-maintained list of every platform repository with its archetype spelled out beside it; now
 * it adopts the wrapper and reconciles, and the wrapper's own {@code .gitmodules} is that list. The
 * {@code qits-qits.git} fixture carries a real one, whose relative entries fold against the fixtures
 * directory and land on the sibling bares — the same resolution a forge performs.
 *
 * <p>The in-code manifest is down to the wrapper alone. The pre-split monorepo used to sit beside
 * it and is out of the platform: a repository qits does not build, deploy or provision from has no
 * business in a list that describes qits.
 */
@QuarkusTest
@TestProfile(SelfSeedServiceTest.TestProfile.class)
public class SelfSeedServiceTest {

  /**
   * The wrapper slot. Its basename must be exactly {@code qits-qits} or the adopt check rejects it,
   * which is the point: it proves the strict {@code <slug>-<slug>} rule holds for the project it was
   * built for. It carries a committed {@code .gitmodules} declaring two resolvable components and
   * one under a directory no archetype claims.
   */
  static final String QITS_WRAPPER_FIXTURE = "qits-qits.git";

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path projectsDataDir = Files.createTempDirectory("qits-test-self-seed-projects");
        return Map.of(
            // The mirror root, isolated to this class. FakeGitHostAddress's fake host root is a
            // fixed literal (target/qits-test-host), wiped per class by RepoDataDirReset regardless
            // of this profile's own temp dir.
            "qits.projects.data-dir", projectsDataDir.toString(),
            // Padded on purpose (a trailing newline is how an env file / k8s ConfigMap value
            // arrives) so the whole suite exercises the manifest-side trim: without it the manifest
            // would never re-match the row it stored trimmed, and every boot would think the
            // wrapper had drifted.
            "qits.startup-seed.wrapper-url", "  " + fixturePath(QITS_WRAPPER_FIXTURE) + "\n");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private static String fixturePath(String name) throws Exception {
      return GitFixtures.path(name);
    }
  }

  @Inject SelfSeedService selfSeedService;
  @Inject ProjectService projectService;
  @Inject RepositoryRepository repositoryRepository;
  @Inject RepositoryNameRepository repositoryNameRepository;
  @Inject RecordingProjectDomainRegistrar domains;
  @Inject GitHostAddress gitHost;

  /** A clean slate each method — {@code @QuarkusTest} shares one in-memory DB across the class. */
  @BeforeEach
  void clean() {
    List.copyOf(projectService.list()).forEach(p -> projectService.delete(p.id));
    domains.clear();
  }

  private String fixture(String name) throws Exception {
    return GitFixtures.path(name);
  }

  /** The qits project, or an assertion failure — there must be exactly one after a reconcile. */
  private Project qitsProject() {
    var projects = projectService.list().stream().filter(p -> "qits".equals(p.name)).toList();
    assertEquals(1, projects.size(), "exactly one 'qits' project");
    return projects.get(0);
  }

  /** The project's repositories keyed by the name they are addressable under. */
  private Map<String, Repository> reposByName(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .collect(
            Collectors.toMap(
                r -> repositoryNameRepository.nameFor(r).orElse(r.id), r -> r, (a, b) -> a));
  }

  @Test
  public void theSeedRegistersExactlyWhatTheWrapperDeclares() {
    selfSeedService.reconcile();

    Project project = qitsProject();
    Map<String, Repository> repos = reposByName(project.id);
    assertEquals(
        Set.of(
            "qits-qits", // the wrapper itself
            "submodule-shared", // libs/ in the wrapper's .gitmodules
            "submodule-grandchild"), // services/ in the wrapper's .gitmodules
        repos.keySet(),
        "the wrapper and one repository per resolvable wrapper entry — and nothing for the entry"
            + " under a directory no archetype claims");

    assertEquals(RepositoryArchetype.LIBRARY, repos.get("submodule-shared").archetype, "libs/");
    assertEquals(
        RepositoryArchetype.SERVICE, repos.get("submodule-grandchild").archetype, "services/");
  }

  /** The manifest is one line, and the platform list it replaced must not creep back. */
  @Test
  public void theInCodeManifestIsJustTheWrapper() {
    assertEquals(1, selfSeedService.manifest().size());
    assertEquals(
        RepositoryArchetype.PROJECT,
        selfSeedService.manifest().get(0).archetype(),
        "the wrapper, which is where every other repository comes from");
  }

  @Test
  public void reRunIsAFullNoOp() {
    selfSeedService.reconcile();
    long reposAfterFirst = repositoryRepository.count();
    Map<String, String> idsAfterFirst =
        reposByName(qitsProject().id).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().id));

    selfSeedService.reconcile();

    assertEquals(reposAfterFirst, repositoryRepository.count(), "re-run adds no repositories");
    assertEquals(
        idsAfterFirst,
        reposByName(qitsProject().id).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().id)),
        "and re-registers no repository under a new id");
    qitsProject(); // still exactly one project (matched by name, not recreated)
  }

  @Test
  public void aWhitespacePaddedOverrideStillMatchesTheRowItStored() throws Exception {
    // Regression (review finding): the profile's wrapper-url override carries a trailing newline,
    // which the creation path stores trimmed. The manifest must trim its own copy too, or every
    // boot would read the row as drifted and repoint it to the padded value.
    selfSeedService.reconcile();
    selfSeedService.reconcile();

    String wrapperId = projectService.findWrapper(qitsProject().id).orElseThrow().id;
    assertEquals(
        1,
        repositoryRepository.count("id = ?1 and url = ?2", wrapperId, fixture(QITS_WRAPPER_FIXTURE)),
        "the padded override matched its trimmed row — the url was never rewritten");
  }

  @Test
  public void halfSeededStateIsCompletedOnTheNextReconcile() throws Exception {
    // A prior boot that created the project and its wrapper but never reached the entries.
    projectService.create("qits", "qits", "pre-existing", fixture(QITS_WRAPPER_FIXTURE));

    selfSeedService.reconcile();

    Map<String, Repository> repos = reposByName(qitsProject().id);
    assertTrue(repos.containsKey("submodule-shared"), "the wrapper's components were registered");
    assertTrue(repos.containsKey("submodule-grandchild"));
  }

  /**
   * The whole point of the rework, and its sharpest consequence: a placeable repository the wrapper
   * does not declare is not part of the project, so the reconcile deregisters its row. Its history
   * on the git host is untouched — putting the entry back re-adopts it.
   */
  @Test
  public void aPlaceableRowTheWrapperDoesNotDeclareIsDeregistered() throws Exception {
    Project project =
        projectService.create("qits", "qits", "pre-existing", fixture(QITS_WRAPPER_FIXTURE));
    Repository stray =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-a.git"), RepositoryArchetype.SERVICE);

    selfSeedService.reconcile();

    // A count query rather than findByIdOptional: the test shares the request-scoped persistence
    // context that loaded this row, and a find would hand back its cached copy.
    assertEquals(
        0,
        repositoryRepository.count("id = ?1", stray.id),
        "a SERVICE row no wrapper entry names loses its row");
    assertTrue(
        Files.isDirectory(Path.of(gitHost.fetchUrl(stray.id))),
        "its repository on the git host is untouched — deregistration is about membership only");
  }

  /**
   * A manifest entry whose row says something else is drift to heal, not a decision to respect —
   * the reason seeding is no longer create-or-skip.
   *
   * <p>Driven with a synthetic entry rather than through {@code reconcile()}: the shipped manifest
   * is the wrapper alone now, and the wrapper's archetype cannot drift (a row is the wrapper by
   * being {@code PROJECT}). The live case that forced this into existence was {@code qits-backend},
   * seeded as a placeable {@code SERVICE} before the archetype rework and therefore one reconcile
   * away from being deregistered for not being a submodule of a wrapper it was never meant to be
   * in. That repository is out of the platform now; the behaviour it bought is not, and it is what
   * the next entry added here will rely on.
   */
  @Test
  public void aRowWhoseArchetypeDriftedFromItsManifestEntryIsCorrected() throws Exception {
    Project project =
        projectService.create("qits", "qits", "pre-existing", fixture(QITS_WRAPPER_FIXTURE));
    Repository drifted =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-super.git"), RepositoryArchetype.SERVICE);

    selfSeedService.assertManifestOntoExistingRow(
        project,
        new SelfSeedService.SeedRepository(
            fixture("submodule-super.git"), RepositoryArchetype.FORK));

    // A count query rather than a field read: this test's persistence context still holds the
    // instance it created, and reading its field would answer from that copy rather than the row.
    assertEquals(
        1,
        repositoryRepository.count(
            "id = ?1 and archetype = ?2", drifted.id, RepositoryArchetype.FORK),
        "the entry says FORK, so the row does");
  }

  /** An entry that names no row heals nothing and, above all, throws nothing. */
  @Test
  public void aManifestEntryWithNoRowIsANoOp() throws Exception {
    Project project =
        projectService.create("qits", "qits", "pre-existing", fixture(QITS_WRAPPER_FIXTURE));

    selfSeedService.assertManifestOntoExistingRow(
        project,
        new SelfSeedService.SeedRepository(
            "https://example.com/never-registered.git", RepositoryArchetype.LIBRARY));

    assertEquals(
        0, repositoryRepository.count("project.id = ?1 and archetype = ?2", project.id,
            RepositoryArchetype.LIBRARY));
  }

  /**
   * The wrapper's url can drift too, and it is the one entry whose url <em>can</em> be healed: it is
   * matched by its role rather than by its url. It matters because every relative {@code
   * ../<name>.git} in the manifest folds against it — a wrapper pointed at the wrong namespace
   * resolves every component onto a url nobody serves.
   */
  @Test
  public void aWrapperPointedAtTheWrongUpstreamIsRepointed() throws Exception {
    Path stale = copyBare(Path.of(fixture(QITS_WRAPPER_FIXTURE)));
    Project project = projectService.create("qits", "qits", "pre-existing", stale.toString());
    String wrapperId = projectService.findWrapper(project.id).orElseThrow().id;

    selfSeedService.reconcile();

    assertEquals(
        1,
        repositoryRepository.count("id = ?1 and url = ?2", wrapperId, fixture(QITS_WRAPPER_FIXTURE)),
        "the wrapper now points where the manifest says, and the adopt that follows is a no-op");
  }

  @Test
  public void aRowThatAlreadyAgreesWithTheManifestIsLeftAlone() {
    selfSeedService.reconcile();
    Map<String, Repository> before = reposByName(qitsProject().id);
    Map<String, String> urls =
        before.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue().url)));

    selfSeedService.reconcile();

    Map<String, Repository> after = reposByName(qitsProject().id);
    assertEquals(before.keySet(), after.keySet());
    for (Map.Entry<String, Repository> entry : after.entrySet()) {
      assertEquals(
          before.get(entry.getKey()).id, entry.getValue().id, entry.getKey() + " is the same row");
      assertEquals(
          before.get(entry.getKey()).archetype,
          entry.getValue().archetype,
          entry.getKey() + " keeps its archetype");
      assertEquals(urls.get(entry.getKey()), String.valueOf(entry.getValue().url));
    }
  }

  /** A throwaway copy of a bare fixture, under the same basename — a second url for one wrapper. */
  private Path copyBare(Path source) throws Exception {
    Path target = Files.createTempDirectory("qits-stale-wrapper").resolve(source.getFileName());
    try (var paths = Files.walk(source)) {
      for (Path from : paths.toList()) {
        Path to = target.resolve(source.relativize(from).toString());
        if (Files.isDirectory(from)) {
          Files.createDirectories(to);
        } else {
          Files.createDirectories(to.getParent());
          Files.copy(from, to);
        }
      }
    }
    return target;
  }

  /** An unplaceable row is exempt from the same sweep, which is why the monorepo is a FORK. */
  @Test
  public void anUnplaceableRowSurvivesTheReconcile() throws Exception {
    Project project =
        projectService.create("qits", "qits", "pre-existing", fixture(QITS_WRAPPER_FIXTURE));
    Repository fork =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-b.git"), RepositoryArchetype.FORK);

    selfSeedService.reconcile();

    assertEquals(1, repositoryRepository.count("id = ?1", fork.id));
  }

  /**
   * The retro-fit: the seeded {@code qits} project gains its wrapper from the manifest's {@code
   * qits-qits} entry. Worth asserting explicitly because {@code reconcile} swallows a failing item
   * into a log line — a broken adoption would otherwise leave every other assertion green.
   */
  @Test
  public void theWrapperIsAdoptedFromTheManifest() throws Exception {
    selfSeedService.reconcile();

    Project project = qitsProject();
    Repository wrapper = projectService.findWrapper(project.id).orElseThrow();

    assertEquals("qits", project.slug);
    assertEquals(RepositoryArchetype.PROJECT, wrapper.archetype);
    assertEquals(fixture(QITS_WRAPPER_FIXTURE), wrapper.url, "the upstream was adopted");
    assertEquals(
        "qits-qits",
        repositoryNameRepository.nameFor(wrapper).orElseThrow(),
        "basename('.../qits-qits.git') is exactly <slug>-<slug> for project qits — which is why"
            + " this url is the right retro-fit and the strict rule needs no escape hatch");
    assertEquals("main", wrapper.mainBranch);
  }

  /**
   * Two reconciles walk the wrapper through adopt states 1 then 3, and the greenfield wrapper
   * {@code ensureProject} creates first means the first reconcile actually exercises state 4.
   */
  @Test
  public void theWrapperAdoptionIsIdempotentAcrossReconciles() {
    selfSeedService.reconcile();
    String firstId = projectService.findWrapper(qitsProject().id).orElseThrow().id;
    long before = repositoryRepository.find("project.id", qitsProject().id).list().size();

    selfSeedService.reconcile();

    assertEquals(firstId, projectService.findWrapper(qitsProject().id).orElseThrow().id);
    assertEquals(
        before,
        repositoryRepository.find("project.id", qitsProject().id).list().size(),
        "the second reconcile adds nothing");
  }

  /**
   * This profile sets none of {@code qits.startup-seed.dns-domain}/{@code -type}/{@code -value},
   * which is the SHIPPED default: the seeded project is created with no domain and registers
   * nothing (main-environment-plan.md §3). The configured half is {@link SelfSeedDnsTest}, which
   * needs a profile of its own.
   */
  @Test
  public void withNoDnsConfiguredTheSeededProjectGetsNoDomain() {
    selfSeedService.reconcile();

    Project project = qitsProject();
    assertNull(project.dns, "the nullable columns exist for exactly this");
    assertTrue(domains.registrations().isEmpty(), "nothing to register, so nothing was asked");
  }

  /**
   * The accepted asymmetry (main-environment-plan.md §3): a reconcile that FINDS the project
   * creates nothing, so the hook does not fire for it. Pinned rather than left implicit — it is the
   * one thing about this feature an operator has to know, and a later change that made the
   * reconcile hook-aware should have to edit this assertion on purpose.
   */
  @Test
  public void aReconcileThatFindsTheProjectFiresNoHooksForIt() {
    selfSeedService.reconcile();
    String projectId = qitsProject().id;
    domains.clear();

    selfSeedService.reconcile();

    assertTrue(
        domains.registrationFor(projectId).isEmpty(),
        "the project was matched by name, not created — one curl closes this per project");
  }
}
