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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
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

  /**
   * "The git host serves nothing yet" is the starting state most of these cases assume, and the fake
   * host is a directory shared by the whole class — so a test that adopts an entry would otherwise
   * decide, by running first, what a later test's create case answers.
   */
  @BeforeEach
  void nothingIsServedYet() throws Exception {
    // A slug is unique (V6) and the fixture wrapper can only be adopted under 'qits' — its basename
    // has to equal <slug>-<slug> — so every case here wants the same slug and the previous case's
    // project has to give it up first. Same idiom as SelfSeedServiceTest's clean().
    projectService.list().stream()
        .filter(p -> "qits".equals(p.slug) || "comp".equals(p.slug))
        .toList()
        .forEach(p -> projectService.delete(p.id));
    for (String name :
        List.of("submodule-shared", "submodule-grandchild", "self-hosted", "sample-javalib")) {
      Path bare = Path.of(gitHost.fetchUrl(name));
      if (!Files.isDirectory(bare)) {
        continue;
      }
      try (var paths = Files.walk(bare)) {
        for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  /** A project whose wrapper is the fixture manifest. The slug must be {@code qits} for it. */
  private Project projectWithManifest(String name) throws Exception {
    return projectService.create(name, "qits", null, fixture("qits-qits.git"));
  }

  /**
   * A project whose wrapper is the MIXED manifest: two entries under {@code components/}, one still
   * under {@code services/}. The slug must be {@code comp} for it — a wrapper is only adoptable from
   * a url whose basename is {@code <slug>-<slug>}.
   */
  private Project projectWithComponentManifest(String name) throws Exception {
    return projectService.create(name, "comp", null, fixture("comp-comp.git"));
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
    assertNotNull(
        byName(project.id, "submodule-shared"),
        "a created row is addressable by the .gitmodules entry name — the alias is what the two"
            + " branches converge on, and the row's own id is an opaque minted key");
    assertNotNull(byName(project.id, "submodule-grandchild"));
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
   * the project. The reconcile says so and stops there — a delete would take the repository off the
   * git host, and one file edit is not consent to that, so a person decides.
   */
  @Test
  public void aPlaceableRowNoEntryNamesIsReportedUndeclaredAndKept() throws Exception {
    Project project = projectWithManifest("Reconcile Undeclared");
    Repository stray =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-a.git"), RepositoryArchetype.SERVICE);

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        WrapperReconcileService.Outcome.UNDECLARED, outcomes.get("submodule-cycle-a").outcome());
    // A count query rather than a find: this test shares the persistence context that loaded the
    // row, and a find would hand back its cached copy.
    assertEquals(1, repositoryRepository.count("id = ?1", stray.id), "the row stays");
    assertTrue(
        Files.isDirectory(Path.of(gitHost.fetchUrl(stray.id))),
        "and so does its repository on the git host");
  }

  @Test
  public void anUnplaceableRowIsNeverExpectedInTheWrapperAndNeverReported() throws Exception {
    Project project = projectWithManifest("Reconcile Fork");
    Repository fork =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-b.git"), RepositoryArchetype.FORK);

    reconcileService.reconcile(project.id);

    assertEquals(1, repositoryRepository.count("id = ?1", fork.id));
  }

  /**
   * An empty manifest is not a manifest. Calling every component of a project undeclared because its
   * wrapper has not started declaring them would report the whole project as strays on the strength
   * of a file that is not there.
   */
  @Test
  public void aWrapperWithNoSubmodulesRegistersNothingAndReportsNothing() throws Exception {
    Project project = projectService.create("Reconcile Empty", "empty-manifest", null);
    Repository repo =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-shared.git"), RepositoryArchetype.SERVICE);

    var reconciliation = reconcileService.reconcile(project.id);

    assertEquals(1, reconciliation.entries().size());
    assertEquals(
        WrapperReconcileService.Outcome.SKIPPED, reconciliation.entries().get(0).outcome());
    assertEquals(1, repositoryRepository.count("id = ?1", repo.id), "nothing was reported");
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
    assertEquals(4, before.entries().size(), "every declared entry, resolved or not");
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

  // -------------------------------------------------------------------------------------------
  // the backup twin, derived from the wrapper rather than stored per row
  // -------------------------------------------------------------------------------------------

  /**
   * A row's {@code url} is its <b>backup twin</b>, never a clone source, and the wrapper is what says
   * where it is: the entry's relative url folded against the wrapper's own forge url is exactly the
   * sibling a clone of the superproject resolves. Deriving it is what makes it uniform — a row that
   * never learned its twin, or learned a stale one, is corrected on the next reconcile.
   */
  @Test
  public void anAdoptedRowLearnsItsBackupTwinFromTheWrapper() throws Exception {
    Project project = projectWithManifest("Reconcile Adopt Twin");
    gitHostRepositories.ensure("submodule-shared", "main");

    reconcileService.reconcile(project.id);

    assertEquals(
        1,
        repositoryRepository.count(
            "id = ?1 and url = ?2", "submodule-shared", fixture("submodule-shared.git")),
        "the adopted row backs up to the sibling the wrapper's relative url folds to, not to null");
  }

  @Test
  public void aRowWhoseBackupTwinIsStaleIsRepointedAndSaysSo() throws Exception {
    Project project = projectWithManifest("Reconcile Retarget");
    // A row addressable as `submodule-shared` whose twin is somewhere else entirely — the shape the
    // live data drifted into.
    Path stale = copyBare(Path.of(fixture("submodule-shared.git")));
    Repository repo =
        projectService.createRepositoryUnderProject(
            project.id, stale.toString(), RepositoryArchetype.LIBRARY);

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        WrapperReconcileService.Outcome.SYNC_TARGET_UPDATED,
        outcomes.get("submodule-shared").outcome());
    assertEquals(
        1,
        repositoryRepository.count("id = ?1 and url = ?2", repo.id, fixture("submodule-shared.git")),
        "the same row, now backing up to the twin the wrapper implies");
  }

  /** An archetype flip and a retarget at once: both applied, the bigger statement reported. */
  @Test
  public void anArchetypeFlipOutranksARetargetInTheOutcome() throws Exception {
    Project project = projectWithManifest("Reconcile Both");
    Path stale = copyBare(Path.of(fixture("submodule-shared.git")));
    Repository repo =
        projectService.createRepositoryUnderProject(
            project.id, stale.toString(), RepositoryArchetype.SERVICE);

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        WrapperReconcileService.Outcome.ARCHETYPE_UPDATED,
        outcomes.get("submodule-shared").outcome(),
        "the archetype change is what a client showing one label per row should show");
    assertEquals(
        1,
        repositoryRepository.count(
            "id = ?1 and url = ?2 and archetype = ?3",
            repo.id,
            fixture("submodule-shared.git"),
            RepositoryArchetype.LIBRARY),
        "and the retarget happened anyway");
  }

  /**
   * A repository cannot be its own backup. An entry pointing back at a qits git host is refused, the
   * row keeps whatever it had, and the reconcile says why rather than silently doing nothing.
   */
  @Test
  public void anEntryPointingAtTheQitsHostIsNeverTakenAsABackupTwin() throws Exception {
    Project project = projectWithManifest("Reconcile Self Host");
    Path twin = copyBare(Path.of(fixture("testing-repo.git")));
    Repository repo =
        projectService.createRepositoryUnderProject(
            project.id, twin.toString(), RepositoryArchetype.SERVICE);
    // Make it the row the `self-hosted` entry matches, by the name the entry's path ends in.
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(() -> repositoryNameRepository.ensureAlias(project, "self-hosted", repo));

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    var outcome = outcomes.get("self-hosted");
    assertTrue(outcome.warning().contains("cannot be its own backup"), outcome.warning());
    assertEquals(
        1,
        repositoryRepository.count("id = ?1 and url = ?2", repo.id, twin.toString()),
        "the row kept the twin it had — a qits-host url is never written");
  }

  // -------------------------------------------------------------------------------------------
  // the component layout: components/<component>/<name>
  // -------------------------------------------------------------------------------------------

  /**
   * The flip's whole point read off one reconcile: the second path segment becomes the row's
   * component, the entry still under {@code services/} keeps working beside it, and a row minted
   * under {@code components/} takes its archetype from the name's role suffix — or nothing at all,
   * because no directory says the kind any more.
   */
  @Test
  public void theComponentLayoutRecordsTheComponentAndReadsTheKindOffTheName() throws Exception {
    Project project = projectWithComponentManifest("Reconcile Components");

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        WrapperReconcileService.Outcome.CREATED, outcomes.get("submodule-shared").outcome());
    assertEquals(
        "components/shared-things/submodule-shared", outcomes.get("submodule-shared").path());
    assertEquals("shared-things", outcomes.get("submodule-shared").component());
    assertEquals(
        "shared-things",
        byName(project.id, "submodule-shared").component,
        "the second path segment is the row's component");
    assertEquals(
        null,
        byName(project.id, "submodule-shared").archetype,
        "'submodule-shared' carries no role suffix, so nothing declares a kind — and null is what"
            + " that is stored as, never a guess this service could not correct afterwards");

    assertEquals("samples", byName(project.id, "sample-javalib").component);
    assertEquals(
        RepositoryArchetype.LIBRARY,
        byName(project.id, "sample-javalib").archetype,
        "'-javalib' is the name grammar's role suffix for a library");

    // The mixed half: an entry the flip has not reached is reconciled exactly as before.
    assertEquals(
        RepositoryArchetype.SERVICE, byName(project.id, "submodule-grandchild").archetype);
    assertEquals(
        null,
        byName(project.id, "submodule-grandchild").component,
        "an archetype-directory entry states no component, so the row carries none");
  }

  /**
   * The rule the live flip depends on: a submodule that only <b>moved</b> keeps the kind it already
   * had. The directory no longer states one, so re-deriving would rewrite — or null — a fact about
   * every repository on the platform on the strength of a path change.
   */
  @Test
  public void aRowMovedIntoAComponentKeepsTheArchetypeItAlreadyHad() throws Exception {
    Project project = projectWithComponentManifest("Reconcile Preserve");
    Repository repo =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-shared.git"), RepositoryArchetype.SERVICE);

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        WrapperReconcileService.Outcome.COMPONENT_UPDATED,
        outcomes.get("submodule-shared").outcome(),
        "the move is what changed, and it is what the line reports");
    assertEquals(RepositoryArchetype.SERVICE, outcomes.get("submodule-shared").archetype());
    // A count query rather than a field read: this test's persistence context still holds the
    // instance it created, and reading its field would answer from that copy rather than the row.
    assertEquals(
        1,
        repositoryRepository.count(
            "id = ?1 and archetype = ?2 and component = ?3",
            repo.id,
            RepositoryArchetype.SERVICE,
            "shared-things"),
        "the row kept its kind and gained its component");
  }

  /**
   * A relative submodule url folds against the superproject's <b>remote</b>, never against the
   * gitlink's own directory, so a three-segment path must derive exactly the backup twin a
   * two-segment one did. Nothing about the flip may move where a repository is backed up to.
   */
  @Test
  public void aDeeperPathDerivesTheSameBackupTwin() throws Exception {
    Project project = projectWithComponentManifest("Reconcile Components Twin");

    reconcileService.reconcile(project.id);

    assertEquals(
        1,
        repositoryRepository.count(
            "id = ?1 and url = ?2",
            byName(project.id, "submodule-shared").id,
            fixture("submodule-shared.git")),
        "components/shared-things/submodule-shared with url ../submodule-shared.git still resolves"
            + " to the sibling beside the wrapper");
  }

  /**
   * The reason null is the honest answer rather than a lossy one: an UNDECLARED line is what puts a
   * repository in front of the delete that destroys it on the git host, and a row nobody has said
   * the kind of is never put there.
   */
  @Test
  public void aRowWithNoArchetypeIsNeverReportedUndeclared() throws Exception {
    Project project = projectWithManifest("Reconcile Unknown Kind");
    Repository stray =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-a.git"), RepositoryArchetype.SERVICE);
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(() -> repositoryService.get(stray.id).archetype = null);

    var outcomes = outcomesByName(reconcileService.reconcile(project.id));

    assertEquals(
        null,
        outcomes.get("submodule-cycle-a"),
        "no line at all — the same silence an unplaceable row gets");
    assertEquals(1, repositoryRepository.count("id = ?1", stray.id), "and the row stays");
  }

  /** A throwaway copy of a bare fixture: a second url under the same basename. */
  private Path copyBare(Path source) throws Exception {
    Path target = Files.createTempDirectory("qits-stale-twin").resolve(source.getFileName());
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
}
