package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Brings a project's rows in line with its wrapper's {@code .gitmodules}.
 *
 * <p>The wrapper <b>is</b> the project's configuration, and this is what makes that true of the
 * database too: every entry gets a row, every placeable row without an entry loses its row, and the
 * directory an entry sits under decides the row's archetype. Importing a wrapper url and running
 * this is how a whole project is restored.
 *
 * <p>It replaces the recursive submodule import, which had the relationship backwards: it walked
 * whatever a repository happened to reference, at any depth, and registered all of it as siblings
 * with a guessed archetype. What belongs to a project is a decision somebody makes and commits, not
 * a graph a crawler found.
 *
 * <p>Per entry, and each one is a decision this class is allowed to make on its own:
 *
 * <ol>
 *   <li>an unknown directory is <b>skipped</b> with a warning — guessing what {@code vendor/} means
 *       would be inventing taxonomy;
 *   <li>a matching row keeps its repository and gains the entry's archetype if they disagree;
 *   <li>no row, but the git host already serves a repository under that name — <b>adopted</b>, id
 *       and history intact;
 *   <li>no row and nothing served, but the entry's url resolves to a reachable backend — <b>cloned
 *       </b>, one level, because the wrapper is the whole manifest and its children's own submodules
 *       are their own business;
 *   <li>anything else is skipped with a warning that says what was missing.
 * </ol>
 *
 * <p>Deregistration is deliberately the lightest possible action ({@link
 * RepositoryService#deregisterRow}): the row goes, the git host's repository and the mirror stay. A
 * removed entry is a statement about membership, and putting the entry back re-adopts the very same
 * repository.
 *
 * <p>Every item is reconciled in its own try/catch. One entry that cannot be resolved never denies
 * the rest, and the outcome list is the answer rather than an exception — the same shape as {@code
 * ProjectReconcileService}'s dns re-assert.
 */
@ApplicationScoped
public class WrapperReconcileService {

  private static final Logger LOG = Logger.getLogger(WrapperReconcileService.class);

  /** What happened to one wrapper entry, or to one row the wrapper no longer names. */
  public enum Outcome {
    /** No row and nothing served: the entry's backend was cloned in. */
    CREATED,
    /** The git host already served it; the existing repository was registered as it stands. */
    ADOPTED,
    /** A row already matched and already agreed. */
    KEPT,
    /** A row already matched; the wrapper's directory changed what kind of component it is. */
    ARCHETYPE_UPDATED,
    /** A placeable row no entry matched: its row is gone, its repository is not. */
    DEREGISTERED,
    /** Nothing could be decided — see the warning. */
    SKIPPED
  }

  /**
   * One line of the reconcile's answer.
   *
   * @param path the wrapper path this is about, or the row's name for a deregistration
   * @param name the addressable name, which is what {@code ../<name>.git} resolves to
   * @param repositoryId the repository the entry now maps to, or null when there is none
   * @param archetype the archetype the directory decided, or the row's own for a deregistration
   * @param warning why an outcome is what it is, when the outcome does not say it; else null
   */
  public record EntryOutcome(
      String path,
      String name,
      String repositoryId,
      RepositoryArchetype archetype,
      Outcome outcome,
      String warning) {}

  /**
   * @param wrapperRepositoryId the wrapper the manifest was read from
   * @param branch the branch it was read at
   */
  public record Reconciliation(
      String projectId, String wrapperRepositoryId, String branch, List<EntryOutcome> entries) {}

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject RepositoryRepository repositoryRepository;

  @Inject RepositoryNameRepository repositoryNameRepository;

  @Inject WrapperSubmoduleWriter wrapperWriter;

  @Inject QitsConfigParser configParser;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitSubmoduleParser submoduleParser;

  /**
   * Reconciles {@code projectId} against its wrapper and answers with what it came to.
   *
   * <p>{@link ActivateRequestContext} because the self-seed calls this on a worker thread with no
   * ambient request context; the individual service calls still own their transactions.
   */
  @ActivateRequestContext
  public Reconciliation reconcile(String projectId) {
    Project project = projectService.get(projectId);
    Repository wrapper =
        projectService
            .findWrapper(projectId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "Project '"
                            + project.name
                            + "' has no wrapper repository, so there is no manifest to reconcile"
                            + " against."));
    String branch = wrapper.mainBranch == null || wrapper.mainBranch.isBlank() ? "main" : wrapper.mainBranch;

    List<WrapperGitmodules.Entry> declared =
        WrapperGitmodules.entries(wrapperWriter.readGitmodules(wrapper));

    List<EntryOutcome> outcomes = new ArrayList<>();
    Set<String> matchedRepoIds = new HashSet<>();
    matchedRepoIds.add(wrapper.id);

    for (WrapperGitmodules.Entry entry : declared) {
      try {
        outcomes.add(reconcileEntry(project, entry, matchedRepoIds));
      } catch (RuntimeException e) {
        LOG.errorf(e, "Reconcile of wrapper entry '%s' failed", entry.path());
        outcomes.add(
            new EntryOutcome(
                entry.path(), entry.name(), null, null, Outcome.SKIPPED, e.getMessage()));
      }
    }

    if (declared.isEmpty()) {
      // An empty manifest is not a manifest. Deregistering every component of a project whose
      // wrapper simply has not started declaring them yet would delete the project's contents on
      // the strength of a file that is not there — so the one entry is what turns the wrapper into
      // the configuration, exactly as the membership guard reads it.
      outcomes.add(
          new EntryOutcome(
              null,
              null,
              wrapper.id,
              null,
              Outcome.SKIPPED,
              "The wrapper declares no submodules, so nothing was registered and nothing was"
                  + " deregistered. Commit a .gitmodules entry per component and run this again."));
    } else {
      outcomes.addAll(deregisterUnmatched(project, matchedRepoIds));
    }
    return new Reconciliation(projectId, wrapper.id, branch, List.copyOf(outcomes));
  }

  // -------------------------------------------------------------------------------------------
  // one entry
  // -------------------------------------------------------------------------------------------

  private EntryOutcome reconcileEntry(
      Project project, WrapperGitmodules.Entry entry, Set<String> matchedRepoIds) {
    String path = entry.path();
    if (path == null || path.isBlank() || !path.contains("/")) {
      return new EntryOutcome(
          path,
          entry.name(),
          null,
          null,
          Outcome.SKIPPED,
          "A component's path must be <directory>/<name>; '" + path + "' is not.");
    }
    String directory = path.substring(0, path.lastIndexOf('/'));
    String name = path.substring(path.lastIndexOf('/') + 1);
    RepositoryArchetype archetype = RepositoryArchetype.fromDirectory(directory);
    if (archetype == null) {
      return new EntryOutcome(
          path,
          name,
          null,
          null,
          Outcome.SKIPPED,
          "'"
              + directory
              + "' is not one of this project's component directories "
              + RepositoryArchetype.skeletonDirectories()
              + ", so there is no archetype to give it.");
    }

    Repository existing = match(project, entry, name);
    if (existing != null) {
      matchedRepoIds.add(existing.id);
      return adjustExisting(project, existing, path, name, archetype);
    }

    // The host already serves it — the platform's own repositories reach the host without ever
    // passing through this service, and adoption is what gives one a row without disturbing the id
    // every ci run and deployment already carries.
    if (repositoryService.hasExistingOrigin(name)) {
      Repository adopted =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    Repository repo =
                        repositoryService.adoptExistingOrigin(project, name, null, archetype);
                    repositoryNameRepository.ensureAlias(project, name, repo);
                    return repo;
                  });
      matchedRepoIds.add(adopted.id);
      return new EntryOutcome(path, name, adopted.id, archetype, Outcome.ADOPTED, null);
    }

    // Nothing here yet: clone the backend the entry names. This is the restore path — a wrapper url
    // imported into an empty deployment brings the whole project back one entry at a time.
    Optional<String> backend = resolveBackendUrl(project, entry);
    if (backend.isEmpty()) {
      return new EntryOutcome(
          path,
          name,
          null,
          archetype,
          Outcome.SKIPPED,
          "Nothing is served as '"
              + name
              + "' and '"
              + entry.url()
              + "' does not resolve to a backend this service can clone from"
              + " (a relative url needs the wrapper's own backup remote to fold against).");
    }
    Repository created =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo =
                      repositoryService.cloneRepository(backend.get(), archetype, project);
                  repositoryNameRepository.ensureAlias(project, name, repo);
                  return repo;
                });
    matchedRepoIds.add(created.id);
    return new EntryOutcome(path, name, created.id, archetype, Outcome.CREATED, null);
  }

  /**
   * The row this entry is about: by the name it is addressable under first, then by that name as an
   * id (an adopted platform repository is keyed by its directory name), then by the url the entry
   * resolves to.
   */
  private Repository match(Project project, WrapperGitmodules.Entry entry, String name) {
    Repository byName =
        repositoryNameRepository.findRepositoryByProjectAndName(project.id, name).orElse(null);
    if (byName != null) {
      return byName;
    }
    Repository byId = repositoryRepository.findByIdOptional(name).orElse(null);
    if (byId != null && byId.project != null && project.id.equals(byId.project.id)) {
      return byId;
    }
    return resolveBackendUrl(project, entry)
        .flatMap(url -> repositoryRepository.findByUrlInProject(url, project.id))
        .orElse(null);
  }

  /**
   * The row kept, with the wrapper having the last word on what kind of component it is, and its
   * alias re-asserted so {@code ../<name>.git} resolves.
   */
  private EntryOutcome adjustExisting(
      Project project,
      Repository existing,
      String path,
      String name,
      RepositoryArchetype archetype) {
    RepositoryArchetype before = existing.archetype;
    String warning =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo = repositoryService.get(existing.id);
                  repositoryNameRepository.ensureAlias(project, name, repo);
                  if (repo.archetype != archetype) {
                    repo.archetype = archetype;
                  }
                  return recordConfigDisagreement(repo, archetype);
                });
    boolean flipped = before != archetype;
    return new EntryOutcome(
        path,
        name,
        existing.id,
        archetype,
        flipped ? Outcome.ARCHETYPE_UPDATED : Outcome.KEPT,
        warning);
  }

  /**
   * Records — never applies — a committed {@code repository.yml} archetype that disagrees with the
   * directory the wrapper mounts this repository under.
   *
   * <p>The wrapper is the project's configuration, so the directory wins; but a repository whose own
   * committed config says something else is telling its author two different things, and that
   * belongs in the {@code config_warning} column where every other ingestion problem already goes.
   * Best effort: a mirror that is not there yet simply has nothing to read.
   */
  private String recordConfigDisagreement(Repository repo, RepositoryArchetype archetype) {
    RepositoryArchetype committed;
    try {
      java.nio.file.Path gitDir = gitMirrors.of(repo.id).gitDir();
      if (!java.nio.file.Files.isDirectory(gitDir)) {
        return null;
      }
      QitsConfig config = configParser.readConfig(gitDir.toFile(), repo.mainBranch);
      committed = config.repository() == null ? null : config.repository().archetype();
    } catch (RuntimeException e) {
      LOG.debugf("Could not read the committed config of %s: %s", repo.id, e.getMessage());
      return null;
    }
    if (committed == null || committed == archetype) {
      repo.configWarning = null;
      return null;
    }
    String warning =
        "The committed repository config declares archetype "
            + committed
            + ", but the wrapper mounts this repository under '"
            + archetype.directory()
            + "'. The wrapper wins; move the submodule to change the archetype, or drop the"
            + " committed value.";
    repo.configWarning = warning;
    return warning;
  }

  /**
   * The absolute backend url an entry names, folded against the wrapper's own backup remote for a
   * relative one. Empty when there is nothing to fold against, or when the result would point back
   * at qits' own git host (which would make the clone mirror this platform's cache instead of the
   * real backend).
   */
  private Optional<String> resolveBackendUrl(Project project, WrapperGitmodules.Entry entry) {
    String raw = entry.url();
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    if (!trimmed.startsWith("./") && !trimmed.startsWith("../")) {
      return Optional.of(trimmed).filter(url -> !submoduleParser.isQitsHostUrl(url));
    }
    return projectService
        .findWrapper(project.id)
        .map(wrapper -> wrapper.url)
        .filter(url -> url != null && !url.isBlank())
        .map(url -> submoduleParser.resolveSubmoduleUrl(url, trimmed))
        .filter(url -> !submoduleParser.isQitsHostUrl(url));
  }

  // -------------------------------------------------------------------------------------------
  // the other direction
  // -------------------------------------------------------------------------------------------

  /**
   * Deregisters every placeable row of the project that no wrapper entry claimed. Unplaceable rows
   * ({@code FORK}, {@code SERVICE_TEMPLATE}) and the wrapper itself are left alone — they were never
   * expected in the manifest.
   */
  private List<EntryOutcome> deregisterUnmatched(Project project, Set<String> matchedRepoIds) {
    List<Repository> strays =
        repositoryRepository.find("project.id", project.id).list().stream()
            .filter(repo -> !matchedRepoIds.contains(repo.id))
            .filter(repo -> repo.archetype != null && repo.archetype.isPlaceable())
            .toList();
    List<EntryOutcome> outcomes = new ArrayList<>();
    for (Repository stray : strays) {
      String name = repositoryNameRepository.nameFor(stray).orElse(stray.id);
      try {
        repositoryService.deregisterRow(stray.id);
        outcomes.add(
            new EntryOutcome(
                null,
                name,
                stray.id,
                stray.archetype,
                Outcome.DEREGISTERED,
                "No wrapper entry names this repository, so it is not part of the project. Its"
                    + " history on the git host is untouched — re-add the entry to bring it back."));
      } catch (RuntimeException e) {
        LOG.errorf(e, "Could not deregister repository %s", stray.id);
        outcomes.add(
            new EntryOutcome(
                null, name, stray.id, stray.archetype, Outcome.SKIPPED, e.getMessage()));
      }
    }
    return outcomes;
  }

  /**
   * The wrapper's manifest as the UI reads it: what the file says, joined to the rows it resolved
   * to. An entry with a null {@code repositoryId} is one the project has no repository for, which is
   * exactly the "out of sync" the reconcile button is for.
   */
  public WrapperView view(String projectId) {
    Optional<Repository> wrapper = projectService.findWrapper(projectId);
    if (wrapper.isEmpty()) {
      return null;
    }
    Repository repo = wrapper.get();
    String branch = repo.mainBranch == null || repo.mainBranch.isBlank() ? "main" : repo.mainBranch;
    List<WrapperView.Entry> entries = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (WrapperGitmodules.Entry entry : WrapperGitmodules.entries(readQuietly(repo))) {
      String path = entry.path();
      if (path == null || path.isBlank() || !seen.add(path)) {
        continue;
      }
      String name = path.substring(path.lastIndexOf('/') + 1);
      String repositoryId =
          repositoryNameRepository
              .findRepositoryByProjectAndName(projectId, name)
              .map(r -> r.id)
              .orElseGet(
                  () ->
                      repositoryRepository
                          .findByIdOptional(name)
                          .filter(r -> r.project != null && projectId.equals(r.project.id))
                          .map(r -> r.id)
                          .orElse(null));
      entries.add(new WrapperView.Entry(path, name, repositoryId));
    }
    return new WrapperView(repo.id, branch, List.copyOf(entries));
  }

  /**
   * The wrapper's manifest as a read surface — see {@link #view}.
   *
   * @param repositoryId the wrapper repository itself, so the UI can show its sync status
   */
  public record WrapperView(String repositoryId, String branch, List<Entry> entries) {
    /**
     * @param repositoryId the repository this entry resolved to, or null when nothing answers to
     *     its name in this project
     */
    public record Entry(String path, String name, String repositoryId) {}
  }

  /** Reading the manifest must never fail a listing — an unreachable wrapper simply has none. */
  private String readQuietly(Repository wrapper) {
    try {
      return wrapperWriter.readGitmodules(wrapper);
    } catch (RuntimeException e) {
      LOG.warnf("Could not read the wrapper's .gitmodules for %s: %s", wrapper.id, e.getMessage());
      return "";
    }
  }
}
