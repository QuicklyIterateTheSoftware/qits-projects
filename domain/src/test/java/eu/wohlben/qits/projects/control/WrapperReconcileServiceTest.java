package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The wrapper-driven reconcile: the wrapper's {@code .gitmodules} decides which repositories the
 * project has and what kind of component each one is.
 *
 * <p>Every case adopts the {@code qits-qits.git} fixture as its wrapper. That fixture commits a real
 * manifest — {@code libs/submodule-shared}, {@code services/submodule-grandchild}, and one entry
 * under {@code vendor/}, which no archetype claims — with relative urls that fold against the
 * fixtures directory and land on the sibling bares, exactly as a forge would resolve them.
 */
@QuarkusTest
public class WrapperReconcileServiceTest {

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WrapperReconcileService reconcileService;
  @Inject RepositoryRepository repositoryRepository;
  @Inject RepositoryNameRepository repositoryNameRepository;
  @Inject GitHostRepositories gitHostRepositories;
  @Inject GitHostAddress gitHost;

  private String fixture(String name) throws Exception {
    return GitFixtures.path(name);
  }

  /** A project whose wrapper is the fixture manifest. The slug must be {@code qits} for it. */
  private Project projectWithManifest(String name) throws Exception {
    return projectService.create(name, "qits", null, fixture("qits-qits.git"));
  }

  private Map<String, WrapperReconcileService.EntryOutcome> outcomesByName(
      WrapperReconcileService.Reconciliation reconciliation) {
    return reconciliation.entries().stream()
        .filter(e -> e.name() != null)
        .collect(
            Collectors.toMap(
                WrapperReconcileService.EntryOutcome::name, Function.identity(), (a, b) -> a));
  }

  private Repository byName(String projectId, String name) {
    return repositoryNameRepository.findRepositoryByProjectAndName(projectId, name).orElse(null);
  }

  @Test
  public void everyResolvableEntryBecomesARepositoryWithTheDirectorysArchetype() throws Exception {
    Project project = projectWithManifest("Reconcile Create");

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        WrapperReconcileService.Outcome.CREATED, outcomes.get("submodule-shared").outcome());
    assertEquals(
        WrapperReconcileService.Outcome.CREATED, outcomes.get("submodule-grandchild").outcome());
    assertEquals(
        RepositoryArchetype.LIBRARY,
        byName(project.id, "submodule-shared").archetype,
        "libs/ decides the archetype — the directory is the taxonomy");
    assertEquals(
        RepositoryArchetype.SERVICE, byName(project.id, "submodule-grandchild").archetype);
    assertEquals(
        "libs/submodule-shared",
        outcomes.get("submodule-shared").path(),
        "the outcome names the path, which is what the UI shows");
  }

  @Test
  public void anEntryUnderADirectoryNoArchetypeClaimsIsSkippedWithAReason() throws Exception {
    Project project = projectWithManifest("Reconcile Skip");

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    var vendored = outcomes.get("vendored");
    assertEquals(WrapperReconcileService.Outcome.SKIPPED, vendored.outcome());
    assertTrue(vendored.warning().contains("vendor"), vendored.warning());
    assertEquals(null, byName(project.id, "vendored"), "and no repository was invented for it");
  }

  @Test
  public void aSecondReconcileKeepsEverythingItRegistered() throws Exception {
    Project project = projectWithManifest("Reconcile Idempotent");
    reconcileService.reconcile(project.id);
    String firstId = byName(project.id, "submodule-shared").id;
    long before = repositoryRepository.count("project.id = ?1", project.id);

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(WrapperReconcileService.Outcome.KEPT, outcomes.get("submodule-shared").outcome());
    assertEquals(firstId, byName(project.id, "submodule-shared").id, "the same repository");
    assertEquals(before, repositoryRepository.count("project.id = ?1", project.id));
  }

  /**
   * The platform's own repositories reach the git host without ever passing through this service, so
   * an entry whose name the host already serves is adopted under that id rather than cloned — which
   * is what keeps every ci run and deployment keyed on it attached to the row.
   */
  @Test
  public void anEntryTheHostAlreadyServesIsAdoptedUnderThatId() throws Exception {
    Project project = projectWithManifest("Reconcile Adopt");
    gitHostRepositories.ensure("submodule-shared", "main");

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        WrapperReconcileService.Outcome.ADOPTED, outcomes.get("submodule-shared").outcome());
    assertEquals(
        "submodule-shared",
        byName(project.id, "submodule-shared").id,
        "the row is keyed by the id the host serves it under, not a fresh uuid");
  }

  @Test
  public void aRowWhoseArchetypeDisagreesWithItsDirectoryIsFlipped() throws Exception {
    Project project = projectWithManifest("Reconcile Flip");
    Repository repo =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-shared.git"), RepositoryArchetype.SERVICE);

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        WrapperReconcileService.Outcome.ARCHETYPE_UPDATED,
        outcomes.get("submodule-shared").outcome());
    assertEquals(repo.id, byName(project.id, "submodule-shared").id, "the same row, re-typed");
    // A count query rather than a field read: this test's persistence context still holds the
    // instance it created, and reading its field would answer from that copy rather than the row.
    assertEquals(
        1,
        repositoryRepository.count(
            "id = ?1 and archetype = ?2", repo.id, RepositoryArchetype.LIBRARY),
        "the row now carries the archetype its wrapper directory declares");
  }

  /**
   * The sharp end of the model: a placeable repository the wrapper does not declare is not part of
   * the project. Its row goes; its history does not, so re-adding the entry re-adopts it.
   */
  @Test
  public void aPlaceableRowNoEntryNamesIsDeregisteredButItsHistorySurvives() throws Exception {
    Project project = projectWithManifest("Reconcile Deregister");
    Repository stray =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-a.git"), RepositoryArchetype.SERVICE);

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        WrapperReconcileService.Outcome.DEREGISTERED,
        outcomes.get("submodule-cycle-a").outcome());
    // A count query rather than a find: this test shares the persistence context that loaded the
    // row, and a find would hand back its cached copy.
    assertEquals(0, repositoryRepository.count("id = ?1", stray.id));
    assertTrue(
        Files.isDirectory(Path.of(gitHost.fetchUrl(stray.id))),
        "the git host's repository is untouched — deregistering is about membership only");
  }

  @Test
  public void anUnplaceableRowIsNeverExpectedInTheWrapperAndNeverDeregistered() throws Exception {
    Project project = projectWithManifest("Reconcile Fork");
    Repository fork =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-b.git"), RepositoryArchetype.FORK);

    reconcileService.reconcile(project.id);

    assertEquals(1, repositoryRepository.count("id = ?1", fork.id));
  }

  /**
   * An empty manifest is not a manifest. Deregistering every component of a project whose wrapper
   * has not started declaring them would delete its contents on the strength of a file that is not
   * there.
   */
  @Test
  public void aWrapperWithNoSubmodulesRegistersNothingAndDeregistersNothing() throws Exception {
    Project project = projectService.create("Reconcile Empty", "empty-manifest", null);
    Repository repo =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-shared.git"), RepositoryArchetype.SERVICE);

    var reconciliation = reconcileService.reconcile(project.id);

    assertEquals(1, reconciliation.entries().size());
    assertEquals(
        WrapperReconcileService.Outcome.SKIPPED, reconciliation.entries().get(0).outcome());
    assertEquals(1, repositoryRepository.count("id = ?1", repo.id), "nothing was deregistered");
  }

  @Test
  public void aProjectWithNoWrapperHasNothingToReconcileAgainst() {
    Project project = projectService.create("Reconcile No Wrapper", "no-wrapper", null);
    repositoryService.deleteInternal(projectService.findWrapper(project.id).orElseThrow().id);

    assertThrows(BadRequestException.class, () -> reconcileService.reconcile(project.id));
  }

  /** The read surface the UI derives in/out-of-sync from. */
  @Test
  public void theWrapperViewJoinsTheManifestToTheRowsItResolvedTo() throws Exception {
    Project project = projectWithManifest("Reconcile View");

    var before = reconcileService.view(project.id);
    assertEquals("main", before.branch());
    assertEquals(
        projectService.findWrapper(project.id).orElseThrow().id, before.repositoryId());
    assertEquals(3, before.entries().size(), "every declared entry, resolved or not");
    assertTrue(
        before.entries().stream().allMatch(e -> e.repositoryId() == null),
        "nothing is registered yet, which is exactly the drift the reconcile button is for");

    reconcileService.reconcile(project.id);

    var after = reconcileService.view(project.id);
    var shared =
        after.entries().stream()
            .filter(e -> "submodule-shared".equals(e.name()))
            .findFirst()
            .orElseThrow();
    assertEquals("libs/submodule-shared", shared.path());
    assertNotNull(shared.repositoryId());
    assertEquals(
        null,
        after.entries().stream()
            .filter(e -> "vendored".equals(e.name()))
            .findFirst()
            .orElseThrow()
            .repositoryId(),
        "the unclaimed directory's entry stays unresolved, and the UI can say so");
  }
}
