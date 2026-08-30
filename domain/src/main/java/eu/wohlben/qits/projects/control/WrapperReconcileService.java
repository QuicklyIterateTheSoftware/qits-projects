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
 * database too: every entry gets a row, every placeable row without an entry is reported as
 * undeclared, and where an entry sits decides what kind of component it is. Importing a wrapper url
 * and running this is how a whole project is restored.
 *
 * <p><b>Two wrapper layouts, both read here</b> ({@link WrapperPath}), because the flip from one to
 * the other is gradual and a mixed wrapper has to reconcile correctly on the way:
 *
 * <ul>
 *   <li>{@code <directory>/<name>} — the directory is the archetype, exactly as before, and the row's
 *       {@code component} stays null.
 *   <li>{@code components/<component>/<name>} — the second segment is the row's {@code component}.
 *       The directory declares no archetype here, so <b>an existing row keeps the archetype it
 *       has</b>: a submodule that only moved must not be re-typed, and must certainly not be nulled.
 *       A row this reconcile <em>mints</em> takes its archetype from the name's role suffix ({@link
 *       eu.wohlben.qits.projects.entity.RepositoryArchetype#fromRepositoryName}), and null when the
 *       name declares none — which is the honest answer and the least destructive one, since a null
 *       archetype is never reported undeclared and so is never offered for the delete that would
 *       destroy the repository, while a guessed one would be a wrong label nothing here can correct.
 * </ul>
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
 * <p><b>This reconcile deletes nothing (2026-08-26).</b> A placeable row no entry names is
 * reported {@code UNDECLARED} and left exactly as it is. Deleting a repository destroys its history
 * on the git host now, and an edit to one file is not consent to that — so the answer names the
 * rows, and a person decides in the UI whether to delete one or put its wrapper entry back.
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
    /**
     * A row already matched and kept its kind; the wrapper moved it to a different <b>component</b>
     * — which is what every row reports the first time a wrapper flips to the component layout.
     */
    COMPONENT_UPDATED,
    /**
     * A row already matched and agreed about its archetype; its <b>backup target</b> was wrong or
     * missing and now names the forge twin the wrapper implies.
     */
    SYNC_TARGET_UPDATED,
    /** A placeable row no entry matched: nothing was done to it, and somebody has to decide. */
    UNDECLARED,
    /** Nothing could be decided — see the warning. */
    SKIPPED
  }

  /**
   * One line of the reconcile's answer.
   *
   * <p>Most lines are about a wrapper entry and carry all six fields. Two shapes are not, and a
   * client has to read {@code outcome} to tell them apart:
   *
   * <ul>
   *   <li>an <b>{@code UNDECLARED}</b> line is about a row the wrapper does <em>not</em> name, so
   *       there is no path: {@code path} is null, {@code name} is the row's alias, {@code
   *       repositoryId} is the row still standing and {@code archetype} is the one it carries.
   *   <li>a wrapper that declares nothing answers with a <b>single {@code SKIPPED}</b> line about
   *       the wrapper itself: {@code path}, {@code name} and {@code archetype} are all null and
   *       {@code repositoryId} is the wrapper's own id.
   * </ul>
   *
   * @param path the wrapper path this is about, or null when the line is not about an entry
   * @param name the addressable name, which is what {@code ../<name>.git} resolves to
   * @param repositoryId the repository the entry now maps to, or null when there is none
   * @param archetype the archetype the row now carries — decided by the entry's directory under the
   *     archetype layout, preserved from the row under the component layout, and the row's own when
   *     the line is about a row the wrapper does not name
   * @param component the component the entry's path names, or null for an entry still mounted under
   *     an archetype directory
   * @param warning why an outcome is what it is, when the outcome does not say it; else null
   */
  public record EntryOutcome(
      String path,
      String name,
      String repositoryId,
      RepositoryArchetype archetype,
      String component,
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
                entry.path(), entry.name(), null, null, null, Outcome.SKIPPED, e.getMessage()));
      }
    }

    if (declared.isEmpty()) {
      // An empty manifest is not a manifest. Calling every component of a project undeclared
      // because its wrapper simply has not started declaring them yet would report the whole
      // project as strays — so the one entry is what turns the wrapper into the configuration,
      // exactly as the membership guard reads it.
      outcomes.add(
          new EntryOutcome(
              null,
              null,
              wrapper.id,
              null,
              null,
              Outcome.SKIPPED,
              "The wrapper declares no submodules, so nothing was registered and nothing was"
                  + " reported undeclared. Commit a .gitmodules entry per component and run this"
                  + " again."));
    } else {
      outcomes.addAll(reportUndeclared(project, matchedRepoIds));
    }
    return new Reconciliation(projectId, wrapper.id, branch, List.copyOf(outcomes));
  }

  // -------------------------------------------------------------------------------------------
  // one entry
  // -------------------------------------------------------------------------------------------

  private EntryOutcome reconcileEntry(
      Project project, WrapperGitmodules.Entry entry, Set<String> matchedRepoIds) {
    String path = entry.path();
    WrapperPath parsed = WrapperPath.parse(path);
    if (parsed == null) {
      return new EntryOutcome(
          path,
          entry.name(),
          null,
          null,
          null,
          Outcome.SKIPPED,
          "A component's path must be <directory>/<name> or components/<component>/<name>; '"
              + path
              + "' is not.");
    }
    String name = parsed.name();
    String component = parsed.component();
    if (!parsed.isComponentLayout() && parsed.directoryArchetype() == null) {
      return new EntryOutcome(
          path,
          name,
          null,
          null,
          null,
          Outcome.SKIPPED,
          "'"
              + parsed.directory()
              + "' is not one of this project's component directories "
              + RepositoryArchetype.skeletonDirectories()
              + " and is not '"
              + WrapperPath.COMPONENTS_DIRECTORY
              + "/<component>', so there is no archetype to give it.");
    }

    SyncTarget syncTarget = syncTargetFor(project, entry);
    Repository existing = match(project, entry, name);
    if (existing != null) {
      matchedRepoIds.add(existing.id);
      return adjustExisting(project, existing, parsed, syncTarget);
    }

    // A row nothing matched is one this reconcile is about to mint, and only for one of those is
    // the name allowed to decide the kind — see the class doc.
    RepositoryArchetype archetype =
        parsed.isComponentLayout()
            ? RepositoryArchetype.fromRepositoryName(name)
            : parsed.directoryArchetype();

    // The host already serves it under the entry name as its storage id — a host seeded before this
    // service existed, where the two coordinates happen to coincide (a name is a valid opaque id).
    // Adoption is what gives such a repository a row without disturbing the storage key its bare
    // already lives under; it registers the entry name as the addressable alias in the same
    // transaction. A host that stores UUIDs is reached the other way round: the bootstrap registers
    // the rows itself, so the reconcile finds them by alias in match() and never gets here.
    if (repositoryService.hasExistingOrigin(name)) {
      Repository adopted =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    // Adoption is idempotent on the storage id and answers a pre-existing row
                    // untouched, so whether the row is this call's to write has to be asked first —
                    // otherwise a null archetype meant for a fresh row would clear a real one.
                    boolean minted = repositoryRepository.findByIdOptional(name).isEmpty();
                    // With the derived backup target, not null: an adopted row that never learns
                    // where its forge twin is is a repository nothing backs up, and the derivation
                    // is the same one every other row's target comes from.
                    Repository repo =
                        repositoryService.adoptExistingOrigin(
                            project, name, name, syncTarget.url(), archetype);
                    repositoryNameRepository.ensureAlias(project, name, repo);
                    repo.component = component;
                    if (minted) {
                      // RepositoryService defaults an absent archetype to SERVICE, which is right
                      // for a caller that simply did not say — and wrong here, where "the name
                      // declares no role" is the answer. Write it back as the null it is.
                      repo.archetype = archetype;
                    }
                    return repo;
                  });
      matchedRepoIds.add(adopted.id);
      return new EntryOutcome(
          path, name, adopted.id, archetype, component, Outcome.ADOPTED, syncTarget.warning());
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
          component,
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
                  // The entry name is the row's addressable name: it is what the git host serves
                  // the repository as and what the deployer's image name repeats — never the url
                  // basename, which an entry is free to differ from. The row's id is minted.
                  Repository repo =
                      repositoryService.cloneRepository(backend.get(), archetype, project, name);
                  repositoryNameRepository.ensureAlias(project, name, repo);
                  repo.component = component;
                  // A clone always mints its row, so the archetype is unconditionally this call's
                  // to state — null included. See the adopt arm for why that matters.
                  repo.archetype = archetype;
                  return repo;
                });
    matchedRepoIds.add(created.id);
    return new EntryOutcome(path, name, created.id, archetype, component, Outcome.CREATED, null);
  }

  /**
   * The row this entry is about: by the name it is addressable under first, then by the url the
   * entry resolves to.
   *
   * <p>There is no third arm reading the name as a repository id. An id is an opaque storage key
   * now, so a name matching one would be a coincidence rather than a resolution — and the alias
   * table is written by every creation path, adoption included, so an entry with a row always has
   * an alias to be found by.
   */
  private Repository match(Project project, WrapperGitmodules.Entry entry, String name) {
    Repository byName =
        repositoryNameRepository.findRepositoryByProjectAndName(project.id, name).orElse(null);
    if (byName != null) {
      return byName;
    }
    return resolveBackendUrl(project, entry)
        .flatMap(url -> repositoryRepository.findByUrlInProject(url, project.id))
        .orElse(null);
  }

  /**
   * The row kept, with the wrapper having the last word on where it is backed up to (the entry's
   * url, folded against the wrapper's own), on which component it belongs to, and — under the
   * archetype layout only — on what kind of component it is. Its alias is re-asserted so {@code
   * ../<name>.git} resolves.
   *
   * <p><b>Under the component layout the archetype is not touched at all.</b> The directory says
   * nothing about kind there, so re-deriving one would rewrite a fact the wrapper no longer states —
   * which is exactly what the flip must not do to a live platform's rows.
   *
   * <p>A row can need several corrections at once. All are applied; the outcome reports the biggest
   * statement about the repository, because a client shows one label per row: an archetype flip
   * outranks a component move, which outranks a retarget.
   */
  private EntryOutcome adjustExisting(
      Project project, Repository existing, WrapperPath parsed, SyncTarget syncTarget) {
    String name = parsed.name();
    RepositoryArchetype before = existing.archetype;
    String beforeComponent = existing.component;
    RepositoryArchetype[] after = new RepositoryArchetype[1];
    boolean[] retargeted = new boolean[1];
    String warning =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo = repositoryService.get(existing.id);
                  repositoryNameRepository.ensureAlias(project, name, repo);
                  if (!parsed.isComponentLayout()
                      && repo.archetype != parsed.directoryArchetype()) {
                    repo.archetype = parsed.directoryArchetype();
                  }
                  repo.component = parsed.component();
                  if (syncTarget.url() != null && !syncTarget.url().equals(repo.url)) {
                    LOG.infof(
                        "Reconcile: %s backs up to %s but the wrapper implies %s — repointing it.",
                        name, repo.url, syncTarget.url());
                    repo.url = syncTarget.url();
                    retargeted[0] = true;
                  }
                  after[0] = repo.archetype;
                  return recordConfigDisagreement(repo, parsed, repo.archetype);
                });
    Outcome outcome =
        before != after[0]
            ? Outcome.ARCHETYPE_UPDATED
            : !java.util.Objects.equals(beforeComponent, parsed.component())
                ? Outcome.COMPONENT_UPDATED
                : retargeted[0] ? Outcome.SYNC_TARGET_UPDATED : Outcome.KEPT;
    return new EntryOutcome(
        parsed.path(),
        name,
        existing.id,
        after[0],
        parsed.component(),
        outcome,
        join(warning, syncTarget.warning()));
  }

  /** Both reasons a row can be worth a note, in one field. */
  private static String join(String first, String second) {
    if (first == null) {
      return second;
    }
    return second == null ? first : first + " " + second;
  }

  /**
   * Records — never applies — a committed {@code repository.yml} archetype that disagrees with the
   * archetype the row actually carries.
   *
   * <p>The wrapper is the project's configuration, so the wrapper wins; but a repository whose own
   * committed config says something else is telling its author two different things, and that
   * belongs in the {@code config_warning} column where every other ingestion problem already goes.
   * Best effort: a mirror that is not there yet simply has nothing to read.
   *
   * <p>A row with <b>no</b> archetype has nothing to disagree with, so nothing is recorded and
   * nothing is cleared — that is a component-layout row whose name declares no role, and the
   * committed value is the only statement anyone has made about it.
   */
  private String recordConfigDisagreement(
      Repository repo, WrapperPath parsed, RepositoryArchetype archetype) {
    if (archetype == null) {
      return null;
    }
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
        parsed.isComponentLayout()
            ? "The committed repository config declares archetype "
                + committed
                + ", but this repository is registered as "
                + archetype
                + " and the wrapper mounts it at '"
                + parsed.path()
                + "', which states a component rather than a kind. The row wins; drop the committed"
                + " value, or rename the repository to carry the role suffix it means."
            : "The committed repository config declares archetype "
                + committed
                + ", but the wrapper mounts this repository under '"
                + archetype.directory()
                + "'. The wrapper wins; move the submodule to change the archetype, or drop the"
                + " committed value.";
    repo.configWarning = warning;
    return warning;
  }

  /**
   * Where a component is backed up to, derived from the wrapper rather than stored per row.
   *
   * <p>{@code Repository.url} has always been the <b>backup twin</b> and never a clone source — the
   * platform clones through its own git host, always — and deriving it is what makes it uniform: the
   * wrapper's own url folded with the entry's relative one is, by construction, the sibling a
   * {@code ../<name>.git} resolves to at the forge. The same fold every clone of the superproject
   * performs, so the answer cannot disagree with what a person sees.
   *
   * @param url the target, or null when the wrapper names no forge to fold against (a project whose
   *     wrapper is greenfield has no twin for anything, and that is a normal state)
   * @param warning why there is no target when the reason is worth saying out loud
   */
  private record SyncTarget(String url, String warning) {
    static final SyncTarget NONE = new SyncTarget(null, null);

    static SyncTarget of(String url) {
      return new SyncTarget(url, null);
    }

    static SyncTarget refused(String url) {
      return new SyncTarget(
          null,
          "The backup target this entry implies ("
              + url
              + ") is qits' own git host, so it was not applied — a repository cannot be its own"
              + " backup. Commit the entry's url as a relative one and give the wrapper a forge"
              + " remote.");
    }
  }

  /** See {@link SyncTarget}. */
  private SyncTarget syncTargetFor(Project project, WrapperGitmodules.Entry entry) {
    String raw = entry.url() == null ? "" : entry.url().trim();
    if (raw.isBlank()) {
      return SyncTarget.NONE;
    }
    if (!raw.startsWith("./") && !raw.startsWith("../")) {
      // An absolute entry names its backend outright; there is nothing to fold.
      return submoduleParser.isQitsHostUrl(raw) ? SyncTarget.refused(raw) : SyncTarget.of(raw);
    }
    String wrapperUrl =
        projectService.findWrapper(project.id).map(wrapper -> wrapper.url).orElse(null);
    if (wrapperUrl == null || wrapperUrl.isBlank()) {
      return SyncTarget.NONE;
    }
    String folded = submoduleParser.resolveSubmoduleUrl(wrapperUrl, raw);
    return submoduleParser.isQitsHostUrl(folded) ? SyncTarget.refused(folded) : SyncTarget.of(folded);
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
   * Reports every placeable row of the project that no wrapper entry claimed, and <b>changes
   * nothing</b>. Unplaceable rows ({@code FORK}, {@code SERVICE_TEMPLATE}) and the wrapper itself
   * are left out — they were never expected in the manifest.
   *
   * <p>A row with <b>no</b> archetype is left out too, and that is the whole reason the component
   * layout stores null rather than a guess: an UNDECLARED line is what offers a person the delete
   * that destroys the repository on the git host, and a row nobody has said the kind of must never
   * be put in front of that decision on the strength of a guess.
   */
  private List<EntryOutcome> reportUndeclared(Project project, Set<String> matchedRepoIds) {
    return repositoryRepository.find("project.id", project.id).list().stream()
        .filter(repo -> !matchedRepoIds.contains(repo.id))
        .filter(repo -> repo.archetype != null && repo.archetype.isPlaceable())
        .map(
            stray ->
                new EntryOutcome(
                    null,
                    repositoryNameRepository.nameFor(stray).orElse(stray.id),
                    stray.id,
                    stray.archetype,
                    stray.component,
                    Outcome.UNDECLARED,
                    "No wrapper entry names this repository, so it is not part of the project."
                        + " Delete it from the project setup page, or add the entry back to the"
                        + " wrapper."))
        .toList();
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
      // The shared resolution, not a copy of it: the by-name read serves the same answer, and a
      // second spelling here would let the two disagree about what belongs to the project.
      String repositoryId =
          repositoryService.findByProjectAndName(projectId, name).map(r -> r.id).orElse(null);
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
