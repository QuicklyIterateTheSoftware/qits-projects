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
 * <p>The {@code qits-backend} slot is a {@code FORK}: unplaceable, so it is neither expected in the
 * wrapper nor deregistered for being missing from it.
 */
@QuarkusTest
@TestProfile(SelfSeedServiceTest.TestProfile.class)
public class SelfSeedServiceTest {

  /** The qits-backend slot — the pre-split monorepo, registered as a FORK and nothing more. */
  static final String QITS_BACKEND_FIXTURE = "submodule-super.git";

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
            // arrives) so the whole suite exercises the manifest-side trim: without it the second
            // reconcile in reRunIsAFullNoOp would re-clone a duplicate qits-backend.
            "qits.startup-seed.repo-url", "  " + fixturePath(QITS_BACKEND_FIXTURE) + "\n",
            "qits.startup-seed.wrapper-url", fixturePath(QITS_WRAPPER_FIXTURE));
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
            "submodule-super", // the qits-backend slot, a FORK
            "submodule-shared", // libs/ in the wrapper's .gitmodules
            "submodule-grandchild"), // services/ in the wrapper's .gitmodules
        repos.keySet(),
        "the wrapper, the monorepo fork, and one repository per resolvable wrapper entry — and"
            + " nothing for the entry under a directory no archetype claims");

    assertEquals(RepositoryArchetype.LIBRARY, repos.get("submodule-shared").archetype, "libs/");
    assertEquals(
        RepositoryArchetype.SERVICE, repos.get("submodule-grandchild").archetype, "services/");
    assertEquals(
        RepositoryArchetype.FORK,
        repos.get("submodule-super").archetype,
        "the pre-split monorepo is not a component of qits, and FORK is the honest way to say so");
  }

  /** The manifest is two lines now, and the platform list it replaced must not creep back. */
  @Test
  public void theInCodeManifestIsJustTheWrapperAndTheMonorepo() {
    assertEquals(2, selfSeedService.manifest().size());
    assertTrue(
        selfSeedService.manifest().stream()
            .anyMatch(e -> e.archetype() == RepositoryArchetype.PROJECT),
        "the wrapper, which is where every other repository comes from");
    assertTrue(
        selfSeedService.manifest().stream()
            .anyMatch(e -> e.archetype() == RepositoryArchetype.FORK),
        "the pre-split monorepo, deliberately unplaceable");
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
  public void aWhitespacePaddedOverrideDoesNotReCloneADuplicate() {
    // Regression (review finding): the profile's repo-url override carries a trailing newline,
    // which cloneOne stores trimmed. The manifest must trim its match key too — otherwise the
    // untrimmed override never re-matches its own stored row and a fresh qits-backend is cloned
    // every boot.
    selfSeedService.reconcile();
    selfSeedService.reconcile();

    long superRows =
        repositoryRepository.find("project.id", qitsProject().id).list().stream()
            .filter(r -> r.url != null)
            .filter(r -> Path.of(r.url).getFileName().toString().equals("submodule-super.git"))
            .count();
    assertEquals(1, superRows, "the padded override matched its trimmed row — exactly one clone");
  }

  @Test
  public void halfSeededStateIsCompletedOnTheNextReconcile() throws Exception {
    // A prior boot that created the project and its wrapper but never reached the entries.
    projectService.create("qits", "qits", "pre-existing", fixture(QITS_WRAPPER_FIXTURE));

    selfSeedService.reconcile();

    Map<String, Repository> repos = reposByName(qitsProject().id);
    assertTrue(repos.containsKey("submodule-super"), "the missing qits-backend entry was added");
    assertTrue(repos.containsKey("submodule-shared"), "and the wrapper's components with it");
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
