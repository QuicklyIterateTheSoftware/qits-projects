package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositorySubmodule;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.persistence.RepositorySubmoduleRepository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import eu.wohlben.qits.projects.testsupport.RecordingProjectDomainRegistrar;
import eu.wohlben.qits.projects.testsupport.RecordingProjectEnvironmentNotifier;
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
 * Reconcile coverage for the startup self-seed (see {@code
 * docs/epics/qits-live-deployment/features/2026-07-19_startup-qits-self-seed.md}), offline through
 * the module's global {@code FakeContainerRuntime} — no docker, no GitHub. The manifest urls are
 * redirected to committed fixtures: the qits-backend slot to {@code submodule-super.git} (direct
 * children child-a + shared, and child-a nests grandchild — the depth that exercises the one-level
 * deep import, standing in for the real quarkus-angular child's nested {@code webui} edge), and the
 * wrapper slot to a ref-less {@code qits-qits.git}. Asserts the additive, per-item-idempotent
 * reconcile: creates the project + the clone manifest's repos + siblings + the nested edge, a re-run
 * is a full no-op, a half-seeded state is completed, and rows the manifest does not own are
 * untouched.
 *
 * <p>{@code testing-repo.git} used to stand in for a second clone entry, {@code
 * wohlben/qits-angular-integration}. That entry is gone — the platform's git host serves the same
 * repository as {@code qits-integrations-angular} and {@code platformManifest()} adopts it — so the
 * fixture now plays the role it is better suited to anyway: a row the manifest does <b>not</b> own,
 * which an additive reconcile must leave alone.
 */
@QuarkusTest
@TestProfile(SelfSeedServiceTest.TestProfile.class)
public class SelfSeedServiceTest {

  static final String QITS_BACKEND_FIXTURE = "submodule-super.git";

  /**
   * A submodule-free bare that the manifest does not name — the stand-in for any row the seed did
   * not put there (a user's own repository, or a legacy clone entry since superseded by adoption).
   */
  static final String UNOWNED_FIXTURE = "testing-repo.git";

  /**
   * The wrapper slot points at a REF-LESS bare, the real {@code wohlben/qits-qits} starting state.
   * Its basename must be exactly {@code qits-qits} or the adopt check rejects it — which is the
   * point: it proves the strict {@code <slug>-<slug>} rule holds for the project it was built for.
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
            // arrives)
            // so the whole suite exercises the manifest-side trim: without it the second reconcile
            // in reRunIsAFullNoOp would re-clone a duplicate qits-backend.
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
  @Inject GitHostRepositories gitHostRepositories;
  @Inject ProjectService projectService;
  @Inject RepositoryRepository repositoryRepository;
  @Inject RepositoryNameRepository repositoryNameRepository;
  @Inject RepositorySubmoduleRepository submoduleRepository;
  @Inject RecordingProjectEnvironmentNotifier environments;
  @Inject RecordingProjectDomainRegistrar domains;

  /** A clean slate each method — {@code @QuarkusTest} shares one in-memory DB across the class. */
  @BeforeEach
  void clean() {
    List.copyOf(projectService.list()).forEach(p -> projectService.delete(p.id));
    environments.clear();
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

  /**
   * The project's repositories keyed by the trailing bare-repo name of their url, <b>excluding the
   * wrapper</b> — it is created with the project and (until its upstream is adopted) has no url.
   */
  private Map<String, Repository> reposByName(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .filter(r -> r.archetype != RepositoryArchetype.PROJECT)
        .collect(Collectors.toMap(r -> Path.of(r.url).getFileName().toString(), r -> r));
  }

  private long edgeCount(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .flatMap(r -> submoduleRepository.findByParentId(r.id).stream())
        .count();
  }

  private void assertEdge(String parentId, String path, String expectedChildId) {
    RepositorySubmodule edge =
        submoduleRepository.findByParentId(parentId).stream()
            .filter(e -> e.path.equals(path))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("no edge at path " + path + " under " + parentId));
    assertEquals(expectedChildId, edge.child.id, "edge at " + path + " points at the right child");
  }

  @Test
  public void reconcileSeedsProjectBothReposSiblingsAndTheNestedDeepImportEdge() {
    selfSeedService.reconcile();

    Project project = qitsProject();
    Map<String, Repository> repos = reposByName(project.id);
    assertEquals(
        Set.of(
            "submodule-super.git", // the qits-backend slot
            "submodule-child-a.git", // its direct child
            "submodule-shared.git", // its direct child (diamond) + child-a's, deduped
            "submodule-grandchild.git"), // reached only by the one-level deep import into child-a
        repos.keySet(),
        "the clone manifest's repo, its direct siblings, and the deep-imported grandchild — and"
            + " nothing for the retired qits-angular-integration entry");

    // The deep import descended one level into every direct child of the superproject: child-a's
    // own
    // submodules were imported — the nested edge (the stand-in for the quarkus-angular webui edge).
    assertEdge(
        repos.get("submodule-child-a.git").id,
        "grandchild",
        repos.get("submodule-grandchild.git").id);
  }

  /**
   * The retirement this commit performs, pinned so it cannot be undone by accident. Both spellings
   * of one repository — a {@code wohlben/*} clone entry and an adopted platform id — seeded two rows
   * indistinguishable in the ui, with only the adopted one carrying ci runs. The clone entry is gone
   * and the adopted id is the survivor.
   */
  @Test
  public void theSupersededAngularIntegrationEntryIsGoneFromTheCloneManifest() {
    assertTrue(
        selfSeedService.manifest().stream()
            .noneMatch(e -> e.url().contains("qits-angular-integration")),
        "the legacy wohlben/qits-angular-integration clone entry was removed, not repointed —"
            + " repointing matches on url and would clone a SECOND row beside the legacy one");
    assertEquals(
        2,
        selfSeedService.manifest().size(),
        "what is left to clone: the wrapper and the pre-split qits-backend monorepo");
    assertTrue(
        selfSeedService.platformManifest().stream()
            .anyMatch(e -> "qits-integrations-angular".equals(e.id())),
        "the repository itself did not go away — the platform's git host serves it, so the adopt"
            + " half of the seed owns it now");
  }

  @Test
  public void reRunIsAFullNoOp() {
    selfSeedService.reconcile();
    long reposAfterFirst = repositoryRepository.count();
    long edgesAfterFirst = edgeCount(qitsProject().id);

    selfSeedService.reconcile();

    assertEquals(reposAfterFirst, repositoryRepository.count(), "re-run adds no repositories");
    assertEquals(edgesAfterFirst, edgeCount(qitsProject().id), "re-run adds no edges");
    qitsProject(); // still exactly one project (matched by name, not recreated)
  }

  @Test
  public void aWhitespacePaddedOverrideDoesNotReCloneADuplicate() {
    // Regression (review finding): the profile's repo-url override carries a trailing newline,
    // which
    // cloneOne stores trimmed. The manifest must trim its match key too — otherwise the untrimmed
    // override never re-matches its own stored row and a fresh qits-backend is cloned every boot.
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
    // Simulate a prior boot that created the project and one repository but never reached the
    // qits-backend entry (equally: a grown manifest whose new entry hasn't landed yet). Reconcile
    // must add exactly the missing entry and disturb nothing that is already there.
    Project project = projectService.create("qits", "pre-existing");
    projectService.createRepositoryUnderProject(
        project.id, fixture(UNOWNED_FIXTURE), RepositoryArchetype.SERVICE, false);

    selfSeedService.reconcile();

    Map<String, Repository> repos = reposByName(qitsProject().id);
    assertTrue(
        repos.containsKey("submodule-super.git"), "the missing qits-backend entry was added");
    assertTrue(repos.containsKey("submodule-grandchild.git"), "its deep import ran too");
    assertTrue(repos.containsKey("testing-repo.git"), "the already-present row survived");
  }

  @Test
  public void rowsTheManifestDoesNotOwnAreLeftUntouched() throws Exception {
    // A user-added repository under a pre-existing "qits" project must survive an additive
    // reconcile.
    Project project = projectService.create("qits", "user's own project named qits");
    Repository userRepo =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-a.git"), RepositoryArchetype.SERVICE, false);

    selfSeedService.reconcile();

    Map<String, Repository> repos = reposByName(qitsProject().id);
    Repository stillThere = repos.get("submodule-cycle-a.git");
    assertNotNull(stillThere, "the user's repository is untouched");
    assertEquals(userRepo.id, stillThere.id, "same row, not recreated");
    assertTrue(
        repos.containsKey("submodule-super.git"), "manifest repos were still added alongside");
    assertTrue(repos.containsKey("submodule-grandchild.git"), "including the deep-imported ones");
  }

  /**
   * The retro-fit: the seeded {@code qits} project gains its wrapper from the manifest's {@code
   * qits-qits} entry. Worth asserting explicitly because {@code reconcile} swallows a failing item
   * into a log line — a broken adoption would otherwise leave every other assertion green.
   */
  @Test
  public void theWrapperIsAdoptedFromTheManifestAndSeededWithTheSkeleton() throws Exception {
    selfSeedService.reconcile();

    Project project = qitsProject();
    Repository wrapper = projectService.findWrapper(project.id).orElseThrow();

    assertEquals("qits", project.slug);
    assertEquals(RepositoryArchetype.PROJECT, wrapper.archetype);
    assertEquals(fixture(QITS_WRAPPER_FIXTURE), wrapper.url, "the empty upstream was adopted");
    assertEquals(
        "qits-qits",
        repositoryNameRepository.nameFor(wrapper).orElseThrow(),
        "basename('.../qits-qits.git') is exactly <slug>-<slug> for project qits — which is why"
            + " this url is the right retro-fit and the strict rule needs no escape hatch");
    assertEquals("main", wrapper.mainBranch, "a ref-less upstream is born on main, not master");
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
   * A repository the git host already holds that this service did not create — what the
   * bootstrap's {@code git init --bare -b main} leaves for the platform's own repositories, and the
   * state the adopt half of the seed exists for.
   */
  private void seedGitHostOrigin(String repoId) {
    gitHostRepositories.ensure(repoId, "main");
  }

  private Map<String, Repository> reposById(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .collect(Collectors.toMap(r -> r.id, r -> r));
  }

  /**
   * The onboarding this feature exists for: a repository the platform's git host already serves
   * gains a row whose id <b>is</b> the directory name, so {@code CiRun.repoId} joins against it.
   */
  @Test
  public void platformRepositoriesOnTheGitHostAreAdoptedUnderTheSeededProject() throws Exception {
    seedGitHostOrigin("qits-ci");
    seedGitHostOrigin("qits-spa-ui-components");
    // Not in the manifest at all: a user-provisioned repository, or a directory no longer claimed.
    seedGitHostOrigin("legacy-build-box");

    selfSeedService.reconcile();

    Map<String, Repository> repos = reposById(qitsProject().id);
    Repository ci = repos.get("qits-ci");
    assertNotNull(ci, "the ci repository the run history references is claimed by the project");
    assertEquals(
        "https://github.com/QuicklyIterateTheSoftware/qits-ci.git",
        ci.url,
        "the forge repository .gitmodules records for that module");
    assertEquals(RepositoryArchetype.SERVICE, ci.archetype, "services/ is SERVICE");
    assertEquals("main", ci.mainBranch);

    assertEquals(
        RepositoryArchetype.LIBRARY,
        repos.get("qits-spa-ui-components").archetype,
        "libs/ is LIBRARY — the archetype follows the directory the superproject mounts it under");

    assertNull(
        repos.get("legacy-build-box"),
        "a directory the manifest does not name is not adopted — the seed claims what it knows");
    assertNull(
        repos.get("qits-dns"),
        "a manifest entry with no origin on this host is skipped, not conjured");
  }

  /** Reconciled on every boot, so converging rather than duplicating is the whole contract. */
  @Test
  public void adoptingThePlatformRepositoriesIsIdempotent() throws Exception {
    seedGitHostOrigin("qits-ci");
    selfSeedService.reconcile();
    long after = repositoryRepository.count();
    String firstId = reposById(qitsProject().id).get("qits-ci").id;

    selfSeedService.reconcile();

    assertEquals(after, repositoryRepository.count(), "the second reconcile adopts nothing new");
    assertEquals("qits-ci", firstId);
  }

  /**
   * Every manifest url is the forge namespace plus the id, and every archetype is one the skeleton
   * can place or one the two unplaceable roles above map onto. Cheap to assert, and it is the check
   * that keeps a hand-edited entry from registering a repository under a url nobody can fetch.
   */
  @Test
  public void thePlatformManifestIsWellFormed() {
    for (SelfSeedService.PlatformRepository entry : selfSeedService.platformManifest()) {
      assertTrue(
          entry.url().endsWith("/" + entry.id() + ".git"),
          "the url of " + entry.id() + " is its id in the platform's forge namespace");
      assertTrue(
          entry.url().startsWith("https://github.com/QuicklyIterateTheSoftware/"),
          entry.id() + " points at the platform's forge namespace");
      assertNotNull(entry.archetype());
      assertTrue(
          entry.archetype() != RepositoryArchetype.PROJECT,
          "the wrapper is the clone manifest's, never adopted: " + entry.id());
    }
    assertEquals(
        selfSeedService.platformManifest().size(),
        selfSeedService.platformManifest().stream()
            .map(SelfSeedService.PlatformRepository::id)
            .distinct()
            .count(),
        "no id appears twice");
  }

  @Test
  public void thePlatformManifestCoversTheReleaseTrainInventory() {
    Map<String, RepositoryArchetype> entries =
        selfSeedService.platformManifest().stream()
            .collect(
                Collectors.toMap(
                    SelfSeedService.PlatformRepository::id,
                    SelfSeedService.PlatformRepository::archetype));

    assertEquals(RepositoryArchetype.SERVICE, entries.get("qits-idp"));
    for (String library :
        List.of("qits-eventstream", "qits-spa-ui-components", "qits-userflows", "qits-oci")) {
      assertEquals(RepositoryArchetype.LIBRARY, entries.get(library), library);
    }
    for (String integration :
        List.of("qits-integrations-angular", "qits-integrations-quarkus")) {
      assertEquals(RepositoryArchetype.INTEGRATION, entries.get(integration), integration);
    }
    for (String frontend :
        List.of(
            "qits-spa-home",
            "qits-spa-projects",
            "qits-spa-workspaces",
            "qits-spa-artifacts",
            "qits-spa-observability",
            "qits-spa-events",
            "qits-spa-ci",
            "qits-spa-cd")) {
      assertEquals(RepositoryArchetype.APPLICATION, entries.get(frontend), frontend);
    }
  }

  /**
   * This profile sets none of {@code qits.startup-seed.dns-domain}/{@code -type}/{@code -value},
   * which is the SHIPPED default: the seeded project is created with no domain, registers nothing,
   * and still gets its {@code main} environment (main-environment-plan.md §3). The configured half
   * is {@link SelfSeedDnsTest}, which needs a profile of its own.
   */
  @Test
  public void withNoDnsConfiguredTheSeededProjectGetsAnEnvironmentAndNoDomain() {
    selfSeedService.reconcile();

    Project project = qitsProject();
    assertNull(project.dns, "the nullable columns exist for exactly this");
    assertTrue(environments.announcementFor(project.id).isPresent());
    assertTrue(domains.registrations().isEmpty(), "nothing to register, so nothing was asked");
  }

  /**
   * The accepted asymmetry (main-environment-plan.md §3): a reconcile that FINDS the project
   * creates nothing, so the hooks do not fire for it. Pinned rather than left implicit — it is the
   * one thing about this feature an operator has to know, and a later change that made the
   * reconcile hook-aware should have to edit this assertion on purpose.
   */
  @Test
  public void aReconcileThatFindsTheProjectFiresNoHooksForIt() {
    selfSeedService.reconcile();
    String projectId = qitsProject().id;
    environments.clear();
    domains.clear();

    selfSeedService.reconcile();

    assertTrue(
        environments.announcementFor(projectId).isEmpty(),
        "the project was matched by name, not created — one curl closes this per project");
  }
}
