package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.entity.BackupOutcome;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.dto.BranchDto;
import eu.wohlben.qits.projects.dto.SyncStatusDto;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.gitmirror.GitMirrorException;
import eu.wohlben.qits.projects.gitmirror.MergeOutcome;
import eu.wohlben.qits.projects.gitmirror.PushOutcome;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RepositoryService {

  private static final Logger LOG = Logger.getLogger(RepositoryService.class);

  /** Backstop for the recursive submodule-closure import (the cycle guard's belt-and-braces). */
  private static final int MAX_SUBMODULE_DEPTH = 10;

  /** {@code -o qits.token=<value>} — the git host's {@code ProtectedRefHook} bypass option. */
  private static final String TOKEN_OPTION_PREFIX = "qits.token=";

  /**
   * The git host's push-token, under the exact config key {@code ProtectedRefHook} itself reads —
   * one deployment value, presented back by whoever pushes. No default, matching the hook: unset
   * means this service presents no token, and a host push that needs one to reach a protected
   * default branch is refused exactly as it was before this existed. A configured-empty value reads
   * as unset too (SmallRye's own rule for {@code Optional<String>}), which is why {@link
   * #withHostToken} still guards against a blank value explicitly rather than trusting that alone.
   */
  @ConfigProperty(name = "qits.repositories.git.push-token")
  Optional<String> pushToken;

  @Inject RepositoryRepository repositoryRepository;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitHostRepositories gitHostRepositories;

  /**
   * SEAM (migration-plan.md §6, repository <-> workspace). Was {@code WorkspaceService} +
   * {@code WorkspaceRepository} + {@code ContainerRuntime}, all three of which are qits-workspaces'
   * (WS_REPO) and read tables in another database. Narrowed to the facts this context asks for and
   * the two things it asks to be done. Both optional — see the interfaces for absent-behaviour.
   */
  @Inject Instance<WorkspaceLookup> workspaces;

  @Inject Instance<WorkspaceLifecycle> workspaceLifecycle;

  @Inject GitExecutor git;

  @Inject GitIdentity gitIdentity;

  @Inject GitRemoteAuth remoteAuth;

  @Inject GitSubmoduleParser submoduleParser;

  @Inject RepositoryNameRepository repositoryNameRepository;

  @Inject ProjectTemplate projectTemplate;

  /** The wrapper's .gitmodules writer — the membership guard reads through it too. */
  @Inject WrapperSubmoduleWriter wrapperWriter;

  /**
   * SEAM (migration-plan.md §6): the technical-process framework is a port here (see {@link
   * TechnicalProcessRegistry}) and is optional. With no implementation the pull/push/sync still run
   * — on the same worker thread, against the same bare origins — but unnarrated, and the begin*
   * methods return a null process id instead of one to subscribe to.
   */
  @Inject Instance<TechnicalProcessRegistry> processes;

  /**
   * Runs {@link #beginPullRepository}'s recursive pull off the request thread — the HTTP call
   * returns the technical-process id immediately and the browser watches the walk repo by repo over
   * SSE. Mirrors {@code WorkspaceService}'s provision executor.
   */
  private final ExecutorService processExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "repository-pull");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    processExecutor.shutdownNow();
  }

  /**
   * Clones an existing external repository under {@code project} — the <b>attach</b> half of the
   * create flow. Its submodules are not followed: the wrapper's {@code .gitmodules} is the project's
   * manifest, so what belongs to the project is what the wrapper says, not what a clone happened to
   * reference.
   */
  @Transactional
  public Repository cloneRepository(String url, RepositoryArchetype archetype, Project project) {
    return cloneOne(url, archetype, project, true);
  }

  /**
   * {@link #cloneRepository(String, RepositoryArchetype, Project)} with the addressable name the
   * caller already holds — the wrapper reconcile's clone branch, where the {@code .gitmodules}
   * entry name is what the git host will serve the repository as and what every sibling {@code
   * ../<name>.git} resolves to. The row's id is that name (see {@link #requireCreatableId}), never
   * the url basename, so a retry after a partial failure lands on the reconcile's adopt branch
   * instead of orphaning a second repository.
   */
  @Transactional
  public Repository cloneRepository(
      String url, RepositoryArchetype archetype, Project project, String name) {
    return cloneOne(url, archetype, project, true, name, false);
  }

  /**
   * Clones one repository under {@code project} and registers its project-scoped name alias (its
   * url basename). Runs within the caller's transaction.
   *
   * @param createMainWorkspace whether to give the repository its default main-branch workspace
   */
  private Repository cloneOne(
      String url, RepositoryArchetype archetype, Project project, boolean createMainWorkspace) {
    return cloneOne(url, archetype, project, createMainWorkspace, null, false);
  }

  /**
   * Clones a project's <b>wrapper repository</b> from an upstream that may be completely empty —
   * the brownfield half of wrapper creation.
   *
   * <p>{@code git clone --mirror} of an empty remote succeeds but yields no refs, and there is no
   * {@code HEAD} for {@link #detectDefaultBranch} to read (it would answer {@code "master"}).
   * Rather than requiring a README be pushed first, HEAD is pointed at {@link
   * #WRAPPER_DEFAULT_BRANCH} and the skeleton is seeded there — so a brand-new, never-pushed-to
   * forge repository is a supported starting state. An upstream that <em>does</em> have history is
   * left completely untouched.
   *
   * @param name the wrapper's project-scoped addressable name, {@code <slug>-<slug>} — also the
   *     row's id, like every other creation path
   */
  @Transactional
  public Repository cloneWrapperOrigin(Project project, String url, String name) {
    return cloneOne(url, RepositoryArchetype.PROJECT, project, true, name, true);
  }

  /**
   * @param selfName the addressable name to register, which is also the row's <b>id</b>; {@code
   *     null} derives both from the url basename
   * @param seedSkeletonIfEmpty whether an upstream that came back with no refs at all should be
   *     given the project template skeleton on {@link #WRAPPER_DEFAULT_BRANCH}
   */
  private Repository cloneOne(
      String url,
      RepositoryArchetype archetype,
      Project project,
      boolean createMainWorkspace,
      String selfName,
      boolean seedSkeletonIfEmpty) {
    if (url == null || url.isBlank()) {
      throw new BadRequestException("url is required");
    }
    String trimmedUrl = url.trim();
    // `url` is user-supplied and passed to `git clone`. Reject a dash-leading value so it can't be
    // smuggled in as a flag (argv flag injection), and the `ext::` transport which lets a remote
    // run
    // arbitrary commands. Local paths and https/ssh/git remotes are all still allowed.
    if (trimmedUrl.startsWith("-") || trimmedUrl.regionMatches(true, 0, "ext::", 0, 5)) {
      throw new BadRequestException("Invalid repository URL: " + trimmedUrl);
    }
    // A repository must point at its real backend, never at qits' own git host — cloning from
    // /git/… would mirror qits' cache back onto itself (a self-referential loop) instead of the
    // upstream. This also enforces the submodule onboarding convention downstream (a child imported
    // from a resolved qits-host url is the exact "points at the qits host" bug the guard prevents).
    if (submoduleParser.isQitsHostUrl(trimmedUrl)) {
      throw new BadRequestException(
          "Refusing to clone from the qits git host ("
              + trimmedUrl
              + "); a repository must point at its real backend, not qits' own cache.");
    }

    Repository repo = new Repository();
    // The id IS the addressable name — the caller's, or the url basename. See requireCreatableId
    // for why a taken or invalid name is a hard failure rather than a fallback id.
    repo.id =
        requireCreatableId(
            project,
            selfName != null ? selfName : RepositoryNameRepository.basename(trimmedUrl));
    repo.url = trimmedUrl;
    repo.archetype = archetype != null ? archetype : RepositoryArchetype.SERVICE;
    repo.project = project;
    repositoryRepository.persist(repo);

    // Give the repository a project-scoped addressable name (its url basename) so the git host can
    // serve it as a sibling under /git/<projectId>/<name> — this is what lets committed relative
    // submodule urls resolve natively, and what its own workspace container clones itself under.
    // requireCreatableId above already proved the name free, so neither branch can fall back to a
    // disambiguated alias here.
    if (selfName != null) {
      registerWrapperName(repo, selfName);
    } else {
      repositoryNameRepository.registerSelfName(repo);
    }

    RepoMirror mirror = gitMirrors.of(repo.id);
    try {
      mirror.cloneFrom(repo.url, remoteAuth);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Git clone failed: " + e.getMessage());
    }

    // An empty upstream mirrors successfully but brings no refs, leaving nothing for a workspace
    // container's clone to land on. Give it the skeleton on `main` instead of demanding the user
    // push a first commit by hand — this is what makes a brand-new, never-pushed-to forge
    // repository a supported starting state. Never reached for an upstream that has history.
    String skeletonCommit = null;
    if (seedSkeletonIfEmpty && !hasAnyRef(mirror.gitDir())) {
      try {
        git.exec(
            mirror.gitDir().toFile(),
            "git",
            "symbolic-ref",
            "HEAD",
            "refs/heads/" + WRAPPER_DEFAULT_BRANCH);
      } catch (Exception e) {
        throw new InternalServerErrorException(
            "Failed to point the empty mirror's HEAD at " + WRAPPER_DEFAULT_BRANCH);
      }
      skeletonCommit = seedProjectTemplate(mirror);
    }

    // The main branch defaults to the remote's default branch (the mirror's HEAD).
    repo.mainBranch = detectDefaultBranch(mirror.gitDir());

    // The git host must hold this repository before anything can be pushed to it (§2.2); idempotent,
    // so a retry after a failed publish below simply re-runs this as a no-op.
    gitHostRepositories.ensure(repo.id, repo.mainBranch);

    if (skeletonCommit != null) {
      // No upstream history existed to import, so there is nothing a suppressed CI run would have
      // saved — the skeleton carries no pipeline config anyway (qits-ci discards the run for want of
      // one). Publish the single root commit onto the main branch.
      publishSingleRef(mirror, skeletonCommit, repo.mainBranch, "the skeleton commit");
    } else {
      // Publish the whole imported history in one push: every branch and every tag. `-o qits.no-ci`
      // suppresses post-receive for this push only (⚖3, BN) — an imported upstream can carry a
      // pipeline config and many branches, and without the option that becomes one CI run per branch
      // against history that predates the platform. A later push to any of these branches fires
      // normally. Explicit wildcard refspecs rather than `--mirror`, which also implies force and
      // would push deletions the host should never take from an import.
      PushOutcome outcome =
          mirror.push(
              withHostToken(
                  eu.wohlben.qits.projects.gitmirror.PushSpec.of(
                          eu.wohlben.qits.projects.gitmirror.PushSpec.Ref.update(
                              "refs/heads/*", "refs/heads/*"),
                          eu.wohlben.qits.projects.gitmirror.PushSpec.Ref.update(
                              "refs/tags/*", "refs/tags/*"))
                      .withOption("qits.no-ci")));
      requireAccepted(outcome, "Failed to publish the imported history");
    }

    // Every repository starts with a default workspace checked out on its main branch, so the main
    // branch is immediately workable and appears as a workspace-backed root in the branch tree.
    // Suppressed for imported children — they materialize inside their superproject's container.
    if (createMainWorkspace && !workspaceLifecycle.isUnsatisfied()) {
      workspaceLifecycle.get().createMainWorkspace(repo.id, repo.mainBranch);
    }

    return repo;
  }

  /**
   * Pushes a single ref — a branch name or a bare commit sha this class just built with {@link
   * RepoMirror#commitTree} — to {@code branch} on the git host, failing loudly on rejection. Used by
   * the creation paths, which publish onto a branch the host has never seen and so have no
   * divergence to reconcile (unlike {@code pushRepository}, which does).
   *
   * <p>Pushing a bare sha moves it to the host but writes no local ref (that is the whole point of
   * pushing by sha rather than by branch name — see {@link RepoMirror#commitTree}), so the mirror
   * refreshes itself from the host afterwards: every reader of this repository, starting with the
   * workspace container this call is immediately followed by, needs {@code refs/heads/<branch>} to
   * resolve locally too, not only on the host.
   */
  private void publishSingleRef(RepoMirror mirror, String source, String branch, String what) {
    PushOutcome outcome =
        mirror.push(
            withHostToken(
                eu.wohlben.qits.projects.gitmirror.PushSpec.of(
                    eu.wohlben.qits.projects.gitmirror.PushSpec.Ref.branch(source, branch))));
    requireAccepted(outcome, "Failed to publish " + what);
    mirror.refreshNow();
  }

  /**
   * Asserts {@code name} can become a new repository row's <b>id</b> and returns it.
   *
   * <p>A repository's id is its addressable name — always. The git host serves the repository under
   * that path segment, the mirror directory carries it, qits-ci's runs repeat it as {@code repoId}
   * and qits-cd derives the image name {@code qits/<repoId>:<sha>} from it. So the id must fit
   * {@link #ADOPTABLE_ID} (it is that host route's segment), and a name that is invalid or already
   * taken is a hard failure: silently minting a fallback id would create a repository nothing
   * downstream can refer to, which is exactly the bug this rule removed. There are no plausible
   * name conflicts within a project — a submodule that occurs twice is the same repository twice —
   * so a collision is a real error worth stopping on, never something to route around.
   */
  private String requireCreatableId(Project project, String name) {
    if (name == null || !ADOPTABLE_ID.matcher(name).matches()) {
      throw new BadRequestException(
          "Invalid repository name '"
              + name
              + "': the name becomes the repository's id — the git host's path segment, the CI"
              + " run's repoId and the deployed image's name — so it must be 1-64 characters of"
              + " letters, digits and inner dashes.");
    }
    repositoryRepository
        .findByIdOptional(name)
        .ifPresent(
            owner -> {
              throw new BadRequestException(
                  "The name '"
                      + name
                      + "' is already taken: a repository with that id exists"
                      + (owner.project != null ? " in project " + owner.project.id : "")
                      + ". A repository's id is its addressable name and there is no fallback id,"
                      + " so the name must be free.");
            });
    repositoryNameRepository
        .findRepositoryByProjectAndName(project.id, name)
        .ifPresent(
            owner -> {
              throw new BadRequestException(
                  "The name '"
                      + name
                      + "' already addresses repository "
                      + owner.id
                      + " in this project. A repository's id is its addressable name and there is"
                      + " no fallback id, so the name must be free.");
            });
    return name;
  }

  /**
   * Creates a <b>blank</b> repository on the platform's own git host, seeded with the repository
   * template — the greenfield half of the create flow, and the sibling of {@link #cloneOne} for a
   * component that has no upstream at all.
   *
   * <p>{@code url} stays null: this repository <em>is</em> hosted here, and the row's url names a
   * backup remote at some external forge, which a blank one does not have yet.
   *
   * <p>The id and the alias are {@code name} <b>exactly</b>, never a fallback: the wrapper records
   * {@code url = ../<name>.git}, and a repository served under a different name is a submodule that
   * resolves nowhere. A taken or invalid name is a hard failure instead — see {@link
   * #requireCreatableId}.
   *
   * <p>Adding it to the wrapper is the caller's next step and deliberately not part of this
   * transaction — see {@code ProjectService.createRepository}.
   */
  @Transactional
  public Repository createBlankRepository(
      Project project, String name, RepositoryArchetype archetype) {
    if (archetype == null || !archetype.isPlaceable()) {
      throw new BadRequestException(
          "A repository created under a project must have a placeable archetype; "
              + archetype
              + " has no directory in the wrapper.");
    }

    Repository repo = new Repository();
    repo.id = requireCreatableId(project, name);
    // The backup twin, derived the same way the reconcile derives every other row's: the wrapper's
    // own forge url folded with ../<name>.git. The forge repository very likely does not exist yet,
    // and that is accepted — the backup push fails loudly in a log line and harmlessly everywhere
    // else, and the day somebody creates the twin it starts working with nothing to configure.
    repo.url = derivedBackupUrl(project, name);
    repo.archetype = archetype;
    repo.project = project;
    repo.mainBranch = WRAPPER_DEFAULT_BRANCH;
    repositoryRepository.persist(repo);
    repositoryNameRepository.registerSelfName(repo, name);

    RepoMirror mirror = gitMirrors.of(repo.id);
    try {
      mirror.initEmpty(WRAPPER_DEFAULT_BRANCH);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Failed to initialize the repository's mirror: " + e.getMessage());
    }

    String skeletonCommit = seedTemplate(mirror, projectTemplate.repositoryEntries(), "repository");
    gitHostRepositories.ensure(repo.id, WRAPPER_DEFAULT_BRANCH);
    // No `-o qits.no-ci`: the skeleton carries no pipeline config, so qits-ci discards the run it
    // fires for want of one — cheaper than special-casing this push.
    publishSingleRef(mirror, skeletonCommit, WRAPPER_DEFAULT_BRANCH, "the repository's skeleton commit");

    if (!workspaceLifecycle.isUnsatisfied()) {
      workspaceLifecycle.get().createMainWorkspace(repo.id, repo.mainBranch);
    }
    return repo;
  }

  /**
   * The forge twin a new component of {@code project} would be backed up to, or null when the
   * project's wrapper names no forge to fold against (a greenfield wrapper has no twin for
   * anything). Never qits' own git host: a repository cannot be its own backup.
   */
  private String derivedBackupUrl(Project project, String name) {
    String wrapperUrl =
        repositoryRepository.findWrapperByProject(project.id).map(w -> w.url).orElse(null);
    if (wrapperUrl == null || wrapperUrl.isBlank()) {
      return null;
    }
    String derived = submoduleParser.resolveSubmoduleUrl(wrapperUrl, "../" + name + ".git");
    return submoduleParser.isQitsHostUrl(derived) ? null : derived;
  }

  /**
   * Mirrors every branch and tag the git host holds onto the repository's <b>backup twin</b> — the
   * whole of what "backed up" means here, and the operation the git host's post-receive now triggers
   * on every accepted push.
   *
   * <p>Not {@code pushRepository}, which is a different verb with a different job: that one
   * publishes <em>one</em> branch and reconciles a divergence, because a person asked it to. This
   * copies what is already the record, refuses nothing and reconciles nothing — a backup that
   * rewrote history to make itself succeed would not be a backup. A non-fast-forward here means the
   * twin holds something the platform does not, which is a real divergence for a person to look at
   * and {@code syncStatus} already reports.
   *
   * <p>Refreshes the mirror first, so what reaches the twin is what the host holds rather than
   * whatever this service last happened to fetch. For an adopted repository that is also the clone:
   * {@link #requireMirror} creates the mirror on first use, which is why a row that has never been
   * pulled or pushed can still be backed up.
   *
   * @return git's own output, for the caller to log
   * <p>{@link ActivateRequestContext} because every caller is on a worker thread with no ambient
   * request context — a git hook's intake hands off to one and the scheduler runs on one — and the
   * row read below needs a persistence context to live in. The individual reads still own their own
   * transactions.
   *
   * @throws BadRequestException when the repository has no backup twin — the caller decides whether
   *     that is worth saying
   */
  @ActivateRequestContext
  public String backupToTwin(String repoId) {
    BackupSpec spec =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo = get(repoId);
                  return new BackupSpec(repo.url, repoLabel(repo));
                });
    if (spec.url() == null || spec.url().isBlank()) {
      throw new BadRequestException("No backup target configured — nothing to back up to");
    }
    RepoMirror mirror = requireMirror(repoId);
    try {
      mirror.refreshNow();
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Backup failed: " + e.getMessage());
    }
    try {
      // Explicit wildcard refspecs rather than --mirror, which also implies force and would push
      // deletions the twin should never take from us.
      return git.exec(
          mirror.gitDir().toFile(),
          remoteAuth.gitWithCredentials(
              "push",
              "--end-of-options",
              spec.url(),
              "refs/heads/*:refs/heads/*",
              "refs/tags/*:refs/tags/*"));
    } catch (Exception e) {
      throw new InternalServerErrorException("Backup of " + spec.label() + " failed: " + e.getMessage());
    }
  }

  /** The scalars a backup needs, read in one short transaction. */
  private record BackupSpec(String url, String label) {}

  /** Every repository that has a backup twin — the scheduled sweep's worklist. */
  public List<String> repositoryIdsWithBackupTwin() {
    return repositoryRepository.find("url is not null and url <> ''").stream()
        .map(repo -> repo.id)
        .toList();
  }

  /** {@link #repositoryIdsWithBackupTwin()} narrowed to one project — the manual trigger's list. */
  public List<String> repositoryIdsWithBackupTwin(String projectId) {
    return repositoryRepository
        .find("project.id = ?1 and url is not null and url <> ''", projectId)
        .stream()
        .map(repo -> repo.id)
        .toList();
  }

  /** The longest {@code last_backup_detail} the column takes — see V5. */
  private static final int MAX_BACKUP_DETAIL = 1000;

  /**
   * Records how a backup attempt went, on the row itself.
   *
   * <p>Written after <em>every</em> attempt, whichever trigger made it, because the value of the
   * record is that it is complete: a repository whose twin has been failing for a week has to look
   * different from one nobody has tried. A success clears the detail — a stale reason sitting beside
   * a green outcome is worse than no reason at all.
   *
   * <p>A row that has since been deleted is not an error to report to anyone: the attempt was
   * against a repository that no longer exists, and there is nothing left to write on.
   */
  @Transactional
  public void recordBackupOutcome(String repoId, BackupOutcome outcome, String detail) {
    Repository repo = repositoryRepository.findByIdOptional(repoId).orElse(null);
    if (repo == null) {
      return;
    }
    repo.lastBackupAt = java.time.Instant.now();
    repo.lastBackupOutcome = outcome;
    repo.lastBackupDetail =
        outcome == BackupOutcome.SUCCEEDED ? null : shortDetail(detail);
  }

  /**
   * The first line of a git failure, capped to the column. git's own message is many lines of
   * progress and advice; the first line is the sentence, and the rest is what the log keeps.
   */
  private static String shortDetail(String detail) {
    if (detail == null || detail.isBlank()) {
      return null;
    }
    String first = detail.strip().lines().findFirst().orElse("").strip();
    if (first.isEmpty()) {
      first = detail.strip();
    }
    return first.length() > MAX_BACKUP_DETAIL ? first.substring(0, MAX_BACKUP_DETAIL) : first;
  }

  /**
   * The sha the <b>git host</b> holds for a repository's main branch — what a wrapper gitlink is
   * pinned to. Read with {@code ls-remote} rather than out of the mirror, because the gitlink names
   * a commit every clone has to be able to fetch, and the host is the only authority on that.
   */
  public Optional<String> mainBranchHeadOnHost(String repoId) {
    Repository repo = get(repoId);
    try {
      return gitMirrors.of(repoId).remoteBranchSha(resolveMainBranch(repo, originPath(repoId)));
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Could not read the head of " + repoLabel(repo) + " on the git host: " + e.getMessage());
    }
  }

  /**
   * The local git directory for {@code repoId} — the mirror's bare git dir (projects-volume-
   * decoupling-plan.md §3.2), with no clone-on-first-use and no freshness check of its own.
   */
  private Path originPath(String repoId) {
    return gitMirrors.of(repoId).gitDir();
  }

  /**
   * {@link #originPath} with the "this repository has a mirror at all" guard, and deliberately
   * nothing more. The pull and push paths call it <em>before</em> their {@code refreshNow()}, and
   * the order is the point: {@code refreshNow()} clones a missing mirror from the git host, so
   * checking afterwards would silently start supporting pull and push on an adopted repository —
   * whose contract is "no mirror, no pull, no push, no workspace" (see {@code adoptExistingOrigin}).
   */
  private Path requireExistingOrigin(String repoId) {
    Path path = originPath(repoId);
    if (!Files.exists(path)) {
      throw new NotFoundException("Repository origin not found on disk");
    }
    return path;
  }

  /**
   * The mirror for {@code repoId}, cloned from the git host on first use and refreshed when the
   * freshness window ({@code qits.projects.git.mirror-freshness-ms}) has lapsed —
   * projects-volume-decoupling-plan.md §3.5. The row check is unchanged (a 404 for an unknown id).
   *
   * <p>A refresh failure is ambiguous between "no such repository on the host" and "the host is
   * unreachable", and the two must not answer the same status: on failure only, {@link
   * GitHostRepositories#find} disambiguates — absent is the same 404 an unknown row already gives,
   * present (or the host still refusing to say) is a 500. One extra network call, on a path that was
   * already about to clone.
   */
  private RepoMirror requireMirror(String repoId) {
    get(repoId);
    RepoMirror mirror = gitMirrors.of(repoId);
    try {
      mirror.refresh();
    } catch (GitMirrorException e) {
      boolean existsOnHost;
      try {
        existsOnHost = gitHostRepositories.find(repoId).isPresent();
      } catch (GitHostException hostError) {
        throw new InternalServerErrorException(
            "Git host unreachable for " + repoId + ": " + hostError.getMessage());
      }
      if (!existsOnHost) {
        throw new NotFoundException("Repository not found on the git host: " + repoId);
      }
      throw new InternalServerErrorException(
          "Could not refresh the mirror for " + repoId + ": " + e.getMessage());
    }
    return mirror;
  }

  /**
   * A repository's human identity for process segment names and log lines. {@code Repository} has
   * no display name, so this is its project-scoped alias — which {@code registerSelfName}
   * guarantees at creation, and which is defined even for a wrapper that has no url to take a
   * basename from.
   */
  private String repoLabel(Repository repo) {
    return repositoryNameRepository.nameFor(repo).orElse(repo.id);
  }

  /**
   * Whether a backup remote is configured — a greenfield wrapper has none until one is attached.
   */
  private static boolean hasBackupRemote(Repository repo) {
    return repo.url != null && !repo.url.isBlank();
  }

  /**
   * The default branch a wrapper repository is born on. A greenfield origin has no remote to
   * inherit a default from, and {@link #detectDefaultBranch} would answer {@code "master"}.
   */
  static final String WRAPPER_DEFAULT_BRANCH = "main";

  /**
   * Creates a project's <b>wrapper repository</b> with a locally-initialized, remote-less bare
   * origin, seeded with the {@link ProjectTemplate} skeleton — the greenfield half of wrapper
   * creation, the sibling of {@link #cloneOne} for a project that has no upstream at all.
   *
   * <p>{@code git init --bare} yields no {@code HEAD} a {@link #detectDefaultBranch} could read, so
   * HEAD is pointed at {@link #WRAPPER_DEFAULT_BRANCH} explicitly and the skeleton commit is what
   * gives that branch a commit to resolve to. Without it a workspace container's clone would land
   * on an unborn branch.
   *
   * <p>{@code url} stays null: a wrapper has no backup remote until one is attached ({@link
   * #attachBackupRemote}). Runs within the caller's transaction.
   *
   * @param name the wrapper's project-scoped addressable name, {@code <slug>-<slug>} — also the
   *     row's id, like every other creation path
   */
  @Transactional
  public Repository initWrapperOrigin(Project project, String name) {
    Repository repo = new Repository();
    repo.id = requireCreatableId(project, name);
    repo.url = null;
    repo.archetype = RepositoryArchetype.PROJECT;
    repo.project = project;
    repo.mainBranch = WRAPPER_DEFAULT_BRANCH;
    repositoryRepository.persist(repo);

    // The wrapper's alias must be exactly <slug>-<slug>, never the disambiguated fallback — see
    // registerWrapperName.
    registerWrapperName(repo, name);

    RepoMirror mirror = gitMirrors.of(repo.id);
    try {
      mirror.initEmpty(WRAPPER_DEFAULT_BRANCH);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Failed to initialize the wrapper repository's mirror: " + e.getMessage());
    }

    String skeletonCommit = seedProjectTemplate(mirror);
    // The git host must hold this repository before the skeleton can be pushed to it (§2.2).
    gitHostRepositories.ensure(repo.id, WRAPPER_DEFAULT_BRANCH);
    // No `-o qits.no-ci`: the skeleton carries no pipeline config, so qits-ci simply discards the
    // run it fires for want of one — cheaper than special-casing this push.
    publishSingleRef(mirror, skeletonCommit, WRAPPER_DEFAULT_BRANCH, "the wrapper's skeleton commit");

    // The workspace container clones from the git host, so it must run after the push above, not
    // before — an earlier clone would find the branch the host still does not have.
    if (!workspaceLifecycle.isUnsatisfied()) {
      workspaceLifecycle.get().createMainWorkspace(repo.id, repo.mainBranch);
    }
    return repo;
  }

  /**
   * Registers the wrapper's addressable name, refusing to fall back to {@code
   * RepositoryNameRepository}'s {@code <name>-<idPrefix>} disambiguation.
   *
   * <p>The whole point of the {@code <slug>-<slug>} rule is that a wrapper's <b>local alias equals
   * its remote basename</b>, which is what makes a committed relative submodule url ({@code
   * ../<name>.git}) resolve identically in a workspace container and at the forge. Silently
   * accepting {@code qits-qits-a1b2c3d4} would destroy that invariant without any error, so a taken
   * name is a hard failure instead.
   *
   * <p>On the creation paths {@link #requireCreatableId} has already proved the name free, so this
   * check only bites on the promotion path ({@link #registerWrapperAlias}), where an existing row
   * gains the wrapper name without a new id being minted.
   */
  private void registerWrapperName(Repository repo, String name) {
    repositoryNameRepository
        .findRepositoryByProjectAndName(repo.project.id, name)
        .filter(owner -> !owner.id.equals(repo.id))
        .ifPresent(
            owner -> {
              throw new BadRequestException(
                  "The name '"
                      + name
                      + "' is already taken in this project by repository "
                      + owner.id
                      + "; a project's wrapper repository must be addressable as '"
                      + name
                      + "' exactly.");
            });
    repositoryNameRepository.registerSelfName(repo, name);
  }

  /**
   * The id shape every repository row carries — created or adopted, the id is the repository's
   * addressable name. Identical to qits-artifacts' git-host route pattern, because the id
   * <em>is</em> the segment that host serves and the directory name under the data dir — so a
   * traversal-shaped id ({@code ..}, a slash, a leading dash) must not be registrable here either.
   */
  private static final Pattern ADOPTABLE_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");

  /** Whether the git host already holds a repository for {@code repoId} — see {@link #adoptExistingOrigin}. */
  public boolean hasExistingOrigin(String repoId) {
    return repoId != null
        && ADOPTABLE_ID.matcher(repoId).matches()
        && gitHostRepositories.find(repoId).isPresent();
  }

  /**
   * Registers a repository that the git host <b>already serves</b> as a repository of {@code
   * project}, keyed by the id the host holds it under.
   *
   * <p>The third creation path, beside {@link #cloneOne} (mirror an upstream into a fresh local
   * mirror) and {@link #initWrapperOrigin} (initialize one locally): here the host already has the
   * repository and this context did not put it there. That is the platform's own git host — the
   * bootstrap creates a bare for every deployable directly on it, so those repositories are real,
   * pushed to and building with no row here at all until adoption gives them one.
   *
   * <p><b>{@code repoId} is the id the host serves it under, and that is the whole point.</b>
   * Everything downstream keys on it: the git host hands it to qits-ci's intake, so {@code
   * CiRun.repoId} carries it, and qits-cd's applications are seeded with it. A row keyed any other
   * way would name a repository nothing in the platform's history refers to. Every creation path
   * keys its row by addressable name for the same reason (see {@code requireCreatableId}); what
   * sets adoption apart is that the name is dictated by what the host already serves rather than
   * chosen by the caller. Hence {@link #ADOPTABLE_ID}.
   *
   * <p>Nothing is cloned and no mirror is created. {@code url} declares which forge repository
   * backs this one (what a reader wants and what {@code RepositoryDto} carries) — it is a row
   * field, nothing more. Fetching from that forge into the platform's own already-built history
   * would risk rewinding refs the ci host has already built from, so an adopted repository gets no
   * mirror, no pull, no push and no workspace: it exists here only so the row can be found by id.
   *
   * <p>No name alias either. An alias is what makes {@code /git/<projectId>/<name>} resolve, and
   * every existing caller reaches these repositories id-addressed at {@code /artifacts/git/<id>}.
   * Registering one eagerly would also have to disambiguate against a basename already taken in the
   * same project, which would produce a name nobody asked for; {@link RepositoryNameResolver}
   * registers one lazily and by the same rules if a workspace ever needs it.
   *
   * <p>Idempotent, and matched on the id rather than the url: a row already carrying {@code repoId}
   * is returned untouched whatever its url, archetype or project. Re-running is the normal case —
   * this is reconciled on every boot.
   *
   * @throws NotFoundException if the git host does not hold {@code repoId}; adoption registers what
   *     is there and never conjures a repository the git host cannot serve
   */
  @Transactional
  public Repository adoptExistingOrigin(
      Project project, String repoId, String url, RepositoryArchetype archetype) {
    if (repoId == null || !ADOPTABLE_ID.matcher(repoId).matches()) {
      throw new BadRequestException(
          "Invalid repository id '"
              + repoId
              + "': an adopted repository is keyed by the id the git host serves it under, so the"
              + " id must be 1-64 characters of letters, digits and inner dashes.");
    }
    if (archetype == RepositoryArchetype.PROJECT) {
      throw new BadRequestException(
          "A repository cannot be adopted with archetype PROJECT: that archetype is reserved for"
              + " the project's wrapper repository, which ProjectService.adoptWrapperRepository"
              + " owns.");
    }

    Optional<Repository> existing = repositoryRepository.findByIdOptional(repoId);
    if (existing.isPresent()) {
      return existing.get();
    }
    GitHostRepositories.HostRepository hostRepo =
        gitHostRepositories
            .find(repoId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "The git host does not hold a repository '" + repoId + "'; nothing to"
                            + " adopt."));
    String trimmedUrl = url == null || url.isBlank() ? null : url.trim();
    // Same argv-injection guard as every other path that stores a url, held here too so a later
    // change that does hand this value to git inherits it rather than having to remember it.
    if (trimmedUrl != null
        && (trimmedUrl.startsWith("-") || trimmedUrl.regionMatches(true, 0, "ext::", 0, 5))) {
      throw new BadRequestException("Invalid repository URL: " + trimmedUrl);
    }

    Repository repo = new Repository();
    repo.id = repoId;
    repo.url = trimmedUrl;
    repo.archetype = archetype != null ? archetype : RepositoryArchetype.SERVICE;
    repo.project = project;
    // Read from what the host reports rather than assumed: the bootstrap's `main` is a convention,
    // and a repository whose host-side default branch says otherwise should say so too.
    repo.mainBranch = hostRepo.defaultBranch();
    repositoryRepository.persist(repo);
    return repo;
  }

  /**
   * Attaches a backup remote to a repository that has none — the wrapper created greenfield, which
   * later gains the forge repository it should be backed up to.
   *
   * <p>A row write and nothing else. Every remote-touching operation on this repository already
   * takes {@code url} as an explicit argument rather than reading a configured remote out of the
   * bare (§3.4), so there is no local git state left to bring in step — attaching the remote here is
   * simply declaring which forge backs this repository up.
   */
  @Transactional
  public Repository attachBackupRemote(String repoId, String url) {
    Repository repo = get(repoId);
    if (url == null || url.isBlank()) {
      throw new BadRequestException("url is required");
    }
    String trimmedUrl = url.trim();
    if (trimmedUrl.startsWith("-") || trimmedUrl.regionMatches(true, 0, "ext::", 0, 5)) {
      throw new BadRequestException("Invalid repository URL: " + trimmedUrl);
    }
    if (submoduleParser.isQitsHostUrl(trimmedUrl)) {
      throw new BadRequestException(
          "Refusing to configure the qits git host ("
              + trimmedUrl
              + ") as a backup remote; it must point at the real backend, not qits' own cache.");
    }
    if (repo.url != null && !repo.url.isBlank()) {
      throw new BadRequestException(
          "Repository already has a backup remote configured (" + repo.url + ").");
    }

    repo.url = trimmedUrl;
    return repo;
  }

  /**
   * Registers {@code name} as an addressable alias of an <em>existing</em> repository being
   * promoted to wrapper. Same no-disambiguation contract as {@link #registerWrapperName}.
   */
  public void registerWrapperAlias(Repository repo, String name) {
    registerWrapperName(repo, name);
  }

  /** Whether the origin has any branch at all — false for a freshly-initialized or empty mirror. */
  private boolean hasAnyRef(Path originPath) {
    try {
      return !git.exec(
              originPath.toFile(),
              "git",
              "for-each-ref",
              "--count=1",
              "--format=%(refname)",
              "refs/heads/")
          .trim()
          .isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Builds the {@link ProjectTemplate} skeleton as a <b>root commit</b> in the mirror's object
   * store, with no ref written — the caller pushes the returned sha to the branch it belongs on.
   *
   * <p>Plumbing and no worktree, exactly as before ({@code hash-object} every blob, {@code
   * update-index --cacheinfo} them into a scratch index, then {@code write-tree} + {@code
   * commit-tree}, the explicit per-entry mode being what lets {@code CLAUDE.md} land as a real git
   * symlink ({@code 120000}) rather than a file) — now run through {@link RepoMirror#writeTree} and
   * {@link RepoMirror#commitTree} rather than directly against a bare origin via {@link
   * GitExecutor}. The mirror module owns the scratch directory and its cleanup.
   *
   * <p>Only ever called for a mirror with nothing to lose — a fresh {@link RepoMirror#initEmpty} or
   * one {@link RepoMirror#cloneFrom} came back with no refs at all. It never overwrites or merges
   * into existing history.
   */
  private String seedProjectTemplate(RepoMirror mirror) {
    return seedTemplate(mirror, projectTemplate.entries(), "project");
  }

  /** {@link #seedProjectTemplate} for any committed skeleton — see {@link ProjectTemplate}. */
  private String seedTemplate(
      RepoMirror mirror, List<ProjectTemplate.TemplateEntry> entries, String what) {
    List<RepoMirror.TreeEntry> treeEntries =
        entries.stream()
            .map(entry -> new RepoMirror.TreeEntry(entry.path(), entry.mode(), entry.content()))
            .toList();
    try {
      String treeSha = mirror.writeTree(treeEntries);
      String commitSha =
          mirror.commitTree(
              treeSha,
              List.of(),
              "Initialize the " + what + " template skeleton",
              gitIdentity.asCommitIdentity());
      LOG.infof(
          "Built the %s template skeleton commit %s for repository %s",
          what, commitSha, mirror.repoId());
      return commitSha;
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Failed to build the " + what + " template skeleton: " + e.getMessage());
    }
  }

  /** The mirror's HEAD points at the remote's default branch (e.g. "master"/"main"). */
  private String detectDefaultBranch(Path originPath) {
    try {
      return git.exec(originPath.toFile(), "git", "symbolic-ref", "--short", "HEAD").trim();
    } catch (Exception e) {
      return "master";
    }
  }

  /** The configured main branch, falling back to the remote's default branch. */
  private String resolveMainBranch(Repository repo, Path originPath) {
    if (repo.mainBranch != null && !repo.mainBranch.isBlank()) {
      return repo.mainBranch;
    }
    return detectDefaultBranch(originPath);
  }

  /**
   * Pulls the backup remote's main branch onto the git host. Fetches the branch from the forge into
   * {@code FETCH_HEAD} and pushes it to the host when the forge is strictly ahead; a no-op when
   * already up to date or when the host is ahead. Diverged histories are reconciled rather than
   * refused: a cleanly-mergeable divergence becomes a real merge commit pushed to the host, and a
   * conflicting one parks the remote tip on {@code merge/<branch>-origin-<branch>} (replacing a
   * previous attempt) and fails with the resolution path in the message — see {@link
   * #buildDivergedMerge}.
   *
   * <p>Every ref this moves is moved by a push, never by an {@code update-ref} in the mirror: a ref
   * written by hand fires no {@code post-receive}, which is why a commit arriving from a forge never
   * produced a CI run. After its own pull, recursively pulls
   * the repository's IMPORTED submodule children (sibling repositories) — a gitlink bump arriving
   * on the superproject's main branch must never point at a commit the child sibling's origin does
   * not yet have, or the workspace container's {@code submodule update} (which clones from that
   * origin via the git host) fails with "Server does not allow request for unadvertised object".
   */
  public String pullRepository(String repoId) {
    requireMembership(repoId);
    return pullRepository(repoId, new HashSet<>(), null, null, new HashSet<>());
  }

  /**
   * The streamed pull: registers a repository-scoped {@link TechnicalProcess} <em>before</em> any
   * git runs (so the currently-fetching repo is visible while its {@code git fetch} blocks on the
   * network), runs the recursive walk on a worker thread, and returns the process id immediately.
   * The browser watches the walk repo by repo over the process's SSE stream — one segment per
   * pulled repository; failures surface there (live, untruncated), not as an HTTP error. Throws 404
   * in-request when the repository doesn't exist, so a bad id still fails fast. Mirrors {@code
   * WorkspaceService.beginEnsureContainer}.
   *
   * <p>Kind-aware single-flight (see {@link TechnicalProcessRegistry#beginForRepository}): a live
   * pull for this repo is reused (its id is returned, no second walk — two walks race the bare
   * origin's ref-locks); a live <em>sync</em> is a conflict (a pull can't ride a sync's push
   * semantics), rejected with a 400. This closes the race even for a client that never learned a
   * pull was running (dialog closed, button clicked again).
   */
  public String beginPullRepository(String repoId) {
    // Validate in-request (unknown id → plain 404, not a process) and name the root segment by the
    // repo's url basename — Repository has no display name; this is the identity the WARNING lines
    // (and reposByName in tests) already use.
    String rootSegment =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  requireWrapperMembership(get(repoId));
                  return "pull:" + repoLabel(get(repoId));
                });
    return switch (beginForRepository(repoId, "pull")) {
      case RepoProcessLease.Reused r -> r.processId();
      case RepoProcessLease.Conflict c -> throw repositoryBusy(c.runningKind());
      case RepoProcessLease.Fresh f -> {
        TechnicalProcess process = f.process();
        // Segment names double as the segment key, so they must be unique across the whole walk:
        // two
        // repos reached under the same relative path (nested levels, or a child path equal to the
        // root basename) would otherwise collide and a failed one's verdict would be swallowed by
        // the first's `ok`. Threaded through the recursion, this allocator disambiguates a repeat
        // with a suffix.
        Set<String> usedSegments = new HashSet<>();
        usedSegments.add(rootSegment);
        processExecutor.submit(
            () -> {
              try {
                pullRepository(repoId, new HashSet<>(), process, rootSegment, usedSegments);
                // No asynchronous second phase: declare an empty service set and settle the
                // provision
                // so `done` fires immediately. A child segment settled `failed` still makes
                // finish()
                // compute overall `done failed`.
                process.expectServices(List.of());
                process.finishProvision(true);
              } catch (RuntimeException e) {
                // Root failure (diverged branch, unreachable remote, auth wall, a host push the git
                // host refused): settle the open root segment failed (appending the message) and
                // emit `done failed`. Idempotent. WARN, not debug — this walk runs off the request
                // thread with no other implementation wired to narrate it (TechnicalProcessRegistry
                // is genuinely optional), so this line is the only place the failure is guaranteed
                // to be visible at all.
                failWithAuthHint(process, e.getMessage(), repoId);
                LOG.warnf(e, "Streamed pull failed for repository %s", repoId);
              }
            });
        yield process.id();
      }
    };
  }

  /**
   * A pull and a sync can't share a walk (a pull would skip the push) nor safely run concurrently
   * against the same bare origin, so a cross-kind request while one is live is rejected. In
   * practice the frontend guard disables the buttons while any repo process is live, so this only
   * ever fires for a second tab / API client that hasn't yet learned a process is running.
   */
  private BadRequestException repositoryBusy(String runningKind) {
    return new BadRequestException(
        "A " + runningKind + " is already running for this repository; wait for it to finish.");
  }

  /**
   * Settle {@code segment} failed, classifying an auth-wall failure with the {@code remote-auth}
   * hint whose target is {@code authRepoId} — the repository whose remote to sign into. For a
   * submodule child that is the <em>child</em>'s id, not the root's, so the sign-in terminal seeds
   * the credentials for the host that actually rejected.
   */
  private void settleWithAuthHint(
      TechnicalProcess process, String segment, String message, String authRepoId) {
    boolean auth = GitRemoteAuth.isAuthFailure(message);
    process.settleSegment(
        segment,
        false,
        auth ? TechnicalProcessFrame.HINT_REMOTE_AUTH : null,
        auth ? authRepoId : null);
  }

  /**
   * {@link #settleWithAuthHint} for the whole-process failure path (settles every open segment).
   */
  private void failWithAuthHint(TechnicalProcess process, String message, String authRepoId) {
    boolean auth = GitRemoteAuth.isAuthFailure(message);
    process.failProvision(
        message, auth ? TechnicalProcessFrame.HINT_REMOTE_AUTH : null, auth ? authRepoId : null);
  }

  /** The scalar snapshot a single repo's pull needs, read in one short transaction. */
  private record PullContext(String url, String branch) {}

  /** A submodule edge flattened to scalars, so it outlives the transaction that loaded it. */
  private record ChildEdge(String path, String childId, String childUrl) {}

  /**
   * {@link #pullRepository(String)} with the recursion state over the imported submodule edge graph
   * and an optional {@link TechnicalProcess} sink: {@code visited} both terminates cycles (the
   * {@code submodule-cycle-*} pair) and dedups the diamond (a shared child is pulled once per
   * invocation — so it gets exactly one segment, under the first edge that reached it, and a cycle
   * never reopens one). With a process, this repo's own pull is streamed as {@code segmentName}
   * (opened at entry, settled when it completes); {@code process}/{@code segmentName} are null for
   * the synchronous callers ({@code syncRepository}), leaving them untouched.
   *
   * <p>Runs on a worker thread when streaming, so every DB touch opens its own transaction.
   */
  private String pullRepository(
      String repoId,
      Set<String> visited,
      TechnicalProcess process,
      String segmentName,
      Set<String> usedSegments) {
    if (!visited.add(repoId)) {
      return "";
    }
    if (process != null && segmentName != null) {
      process.openSegment(segmentName);
    }

    PullContext ctx =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo = get(repoId);
                  return new PullContext(
                      repo.url, resolveMainBranch(repo, gitMirrors.of(repoId).gitDir()));
                });

    // A repository with no backup remote (a greenfield wrapper) has nothing to pull FROM. That is a
    // normal state, not a failure: settle the segment ok and keep walking, since its imported
    // submodule children may well have remotes of their own.
    if (ctx.url() == null || ctx.url().isBlank()) {
      streamLine(process, segmentName, "No backup remote configured — nothing to pull");
      settleOk(process, segmentName);
      return withImportedChildPulls(repoId, "", visited, process, usedSegments);
    }

    // A mirror this service never cloned means an adopted repository, whose contract is "no mirror,
    // no pull, no push, no workspace" (see adoptExistingOrigin). This guard has to come BEFORE
    // refreshNow(), which would otherwise clone one from the host and silently start supporting
    // pull here.
    requireExistingOrigin(repoId);
    RepoMirror mirror = gitMirrors.of(repoId);

    try {
      // The "about to write" rule: every decision below compares the forge's tip against what the
      // git host holds, so the mirror's own refs have to be the host's refs first. Without this the
      // baseline could be an arbitrary number of pushes stale and every verdict would be wrong.
      mirror.refreshNow();
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Git pull failed: " + e.getMessage());
    }

    try {
      // Fetch the forge's branch into FETCH_HEAD only — no ref moves; the reconcile below decides
      // what gets pushed. Streamed line by line into the segment (live progress on a slow fetch;
      // every line stamps the process's activity clock so a long-but-active fetch can't trip the
      // idle reaper). The other pull verbs are single-line and stay post-hoc via streamLine.
      // The tap also collects, because the synchronous callers return the fetch's own output as
      // part of the walk's text and RepoMirror hands back none of it.
      StringBuilder fetched = new StringBuilder();
      Consumer<String> sink = lineSink(process, segmentName);
      mirror.fetchIntoFetchHead(
          ctx.url(),
          ctx.branch(),
          remoteAuth,
          line -> {
            if (fetched.length() > 0) {
              fetched.append('\n');
            }
            fetched.append(line);
            if (sink != null) {
              sink.accept(line);
            }
          });
      String fetchOutput = fetched.toString();
      String remoteSha =
          mirror
              .resolve("FETCH_HEAD")
              .orElseThrow(
                  () ->
                      new InternalServerErrorException(
                          "Git pull failed: the fetch of '"
                              + ctx.branch()
                              + "' left no FETCH_HEAD to read"));
      Optional<String> localOpt = mirror.resolve("refs/heads/" + ctx.branch());
      if (localOpt.isEmpty()) {
        // The git host has no such branch yet — rare (every repository gets its main branch
        // published at creation), but publishing the forge's tip is the only sensible answer.
        requireAccepted(
            mirror.push(
                withHostToken(
                    eu.wohlben.qits.projects.gitmirror.PushSpec.of(
                        eu.wohlben.qits.projects.gitmirror.PushSpec.Ref.branch(
                            remoteSha, ctx.branch())))),
            "Git pull failed");
        streamLine(process, segmentName, "Fast-forwarded to " + shortSha(remoteSha));
        settleOk(process, segmentName);
        return withImportedChildPulls(repoId, fetchOutput, visited, process, usedSegments);
      }
      String localSha = localOpt.get();

      if (remoteSha.equals(localSha) || mirror.isAncestor(remoteSha, localSha)) {
        // Already up to date, or local is ahead — nothing to pull; children may still be stale.
        streamLine(
            process,
            segmentName,
            remoteSha.equals(localSha) ? "Already up to date" : "Local branch is ahead of remote");
        settleOk(process, segmentName);
        return withImportedChildPulls(repoId, fetchOutput, visited, process, usedSegments);
      }
      if (mirror.isAncestor(localSha, remoteSha)) {
        // Remote is strictly ahead — fast-forward, as a push to the host rather than a local
        // update-ref, so receive-pack (and its post-receive) sees the commits arrive.
        requireAccepted(
            mirror.push(
                withHostToken(
                    eu.wohlben.qits.projects.gitmirror.PushSpec.of(
                        eu.wohlben.qits.projects.gitmirror.PushSpec.Ref.branch(
                            remoteSha, ctx.branch())))),
            "Git pull failed");
        streamLine(process, segmentName, "Fast-forwarded to " + shortSha(remoteSha));
        settleOk(process, segmentName);
        return withImportedChildPulls(repoId, fetchOutput, visited, process, usedSegments);
      }
      // Diverged: merge the remote in when the merge is clean; a conflict parks the remote tip on
      // the merge/<branch>-origin-<branch> branch and throws (the message carries the resolution
      // path).
      MergeBuild merge = buildDivergedMerge(mirror, ctx.branch(), localSha, remoteSha);
      // Pull's whole purpose is to advance the platform's own record, so the merge commit goes to
      // the host.
      requireAccepted(
          mirror.push(
              withHostToken(
                  eu.wohlben.qits.projects.gitmirror.PushSpec.of(
                      eu.wohlben.qits.projects.gitmirror.PushSpec.Ref.branch(
                          merge.mergeSha(), ctx.branch())))),
          "Git pull failed");
      streamLine(process, segmentName, merge.verdict());
      settleOk(process, segmentName);
      return withImportedChildPulls(repoId, fetchOutput, visited, process, usedSegments);
    } catch (BadRequestException e) {
      throw e;
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Git pull failed: " + e.getMessage());
    }
  }

  /**
   * Every ref this method moves is moved by a push; a refused one is the operation failing.
   *
   * <p>A refusal the git host phrased as a reason — chiefly {@code ProtectedRefHook} declining an
   * unauthorized update of the repository's default branch — is a statement about the request, not
   * a fault here: it surfaces as a 4xx carrying the hook's own words, the same rule {@link
   * #deleteBranch} already applies to a branch delete refusal. Logged at WARN either way, since a
   * refused pull/push/merge must never be silent — see the caller's async wrapper, which is the only
   * thing standing between this and a 200 nobody can act on. Anything receive-pack did not phrase as
   * a refusal (a transport failure, an unreadable response) stays a 500.
   */
  private static void requireAccepted(PushOutcome outcome, String what) {
    if (outcome.accepted()) {
      return;
    }
    String refusal = outcome.remoteRefusal();
    if (refusal != null) {
      LOG.warnf("%s: the git host refused the push: %s", what, refusal);
      throw new BadRequestException(what + ": the git host refused the push: " + refusal);
    }
    LOG.warnf("%s: %s", what, outcome.output());
    throw new InternalServerErrorException(what + ": " + outcome.output());
  }

  /**
   * Every push this service makes TO THE GIT HOST carries this, except a branch delete: {@link
   * #deleteBranch} calls {@code RepoMirror#deleteBranch} directly and never builds a {@link
   * eu.wohlben.qits.projects.gitmirror.PushSpec} to attach it to — deliberately, since refusing a
   * direct delete of the protected default branch is intended behaviour (proven live) and a token
   * there would bypass it instead of triggering it. Omitted entirely with no token configured, since
   * an absent option changes nothing the hook would have accepted anyway.
   */
  private eu.wohlben.qits.projects.gitmirror.PushSpec withHostToken(
      eu.wohlben.qits.projects.gitmirror.PushSpec spec) {
    return pushToken
        .filter(token -> !token.isBlank())
        .map(token -> spec.withOption(TOKEN_OPTION_PREFIX + token))
        .orElse(spec);
  }

  /**
   * Pulls each submodule child after {@code repoId}'s own successful pull, appending their outputs.
   * A child failure (diverged, unreachable remote) degrades loudly, never blocks: the superproject's
   * pull already succeeded, so the child's error becomes a WARNING line in the returned output (for
   * the synchronous callers) and, when streaming, settles the child's segment {@code failed} while
   * the walk continues to the remaining children.
   *
   * <p><b>The children come from the repository's own {@code .gitmodules}, not from a table.</b>
   * There used to be a {@code repository_submodule} edge table, written by an import that walked the
   * whole closure; the wrapper's manifest replaced the import, and the edges with it. Reading the
   * file on the fly is also simply more correct: it can never be stale, it handles absolute and
   * relative urls alike (a basename is a basename either way), and a submodule whose basename names
   * no sibling in this project is skipped rather than conjuring a row for it — the wrapper decides
   * what belongs to the project, and this walk only decides what to pull.
   *
   * <p>The visited and depth guards are unchanged, because the graph they guard is unchanged: a
   * cyclic pair still terminates, and a diamond is still pulled once.
   */
  private String withImportedChildPulls(
      String repoId,
      String ownOutput,
      Set<String> visited,
      TechnicalProcess process,
      Set<String> usedSegments) {
    List<ChildEdge> edges = QuarkusTransaction.requiringNew().call(() -> childEdgesOf(repoId));
    StringBuilder output = new StringBuilder(ownOutput.trim());
    for (ChildEdge edge : edges) {
      String childSegment = allocateSegment("pull:" + edge.path(), usedSegments);
      try {
        String childOutput =
            pullRepository(edge.childId(), visited, process, childSegment, usedSegments).trim();
        if (!childOutput.isBlank()) {
          output.append('\n').append(childOutput);
        }
      } catch (Exception e) {
        LOG.warnf(
            e, "Pull of imported submodule '%s' of repository %s failed", edge.path(), repoId);
        output
            .append("\nWARNING: pull of imported submodule '")
            .append(edge.path())
            .append("' (")
            .append(edge.childUrl())
            .append(") failed: ")
            .append(e.getMessage());
        if (process != null) {
          process.appendLine(childSegment, "pull failed: " + e.getMessage());
          // Target the CHILD repo: its remote (possibly a different host than the root) is the one
          // that rejected, so the sign-in must seed the child's credentials.
          settleWithAuthHint(process, childSegment, e.getMessage(), edge.childId());
        }
      }
    }
    return output.toString();
  }

  /**
   * {@code repoId}'s {@code .gitmodules} entries resolved to sibling repositories of the same
   * project, by the name each one is addressable under — {@code ../qits-gateway.git} and {@code
   * https://forge/org/qits-gateway.git} both name {@code qits-gateway}, which is exactly the alias
   * the git host serves that sibling at. An entry naming nothing in this project is skipped: it is a
   * dependency of this repository, not a part of this project.
   *
   * <p>Read off the mirror as it stands, with no refresh: the caller has just pulled this repository
   * and refreshed its mirror, so this is the file that arrived with the commit being pulled.
   */
  private List<ChildEdge> childEdgesOf(String repoId) {
    Repository repo = get(repoId);
    Path gitDir = originPath(repoId);
    if (!Files.exists(gitDir)) {
      return List.of();
    }
    List<ChildEdge> edges = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (GitSubmoduleParser.Submodule sub :
        submoduleParser.readSubmodules(gitDir.toFile(), resolveMainBranch(repo, gitDir))) {
      String childName = RepositoryNameRepository.basename(sub.url());
      if (childName.isBlank() || !seen.add(sub.path())) {
        continue;
      }
      repositoryNameRepository
          .findRepositoryByProjectAndName(repo.project.id, childName)
          .filter(child -> !child.id.equals(repo.id))
          .ifPresent(child -> edges.add(new ChildEdge(sub.path(), child.id, child.url)));
    }
    return edges;
  }

  private static String shortSha(String sha) {
    return sha.length() > 12 ? sha.substring(0, 12) : sha;
  }

  /** The branch a conflicting remote tip is parked on, for {@code branch}. */
  static String mergeBranchName(String branch) {
    return "merge/" + branch + "-origin-" + branch;
  }

  /** A built, unpublished merge commit and the verdict line that describes it. */
  private record MergeBuild(String mergeSha, String verdict) {}

  /**
   * Builds the reconciliation of a diverged {@code branch} (neither {@code localSha} nor {@code
   * remoteSha} is an ancestor of the other), and publishes nothing. A clean three-way merge (a real
   * {@code merge-tree --write-tree} in the mirror, no working tree) becomes a real merge commit —
   * local tip as first parent, remote tip as second — whose sha the caller pushes wherever its own
   * job requires: pull pushes it to the git host (advancing the platform's record), push retries the
   * forge with it (satisfying the forge's fast-forward check). A conflicting merge parks the remote
   * tip on {@code merge/<branch>-origin-<branch>} and throws — the message names the conflicting
   * files and the resolution path: merge {@code branch} into the parked branch, resolve, integrate
   * back, then pull/sync/push again.
   */
  private MergeBuild buildDivergedMerge(
      RepoMirror mirror, String branch, String localSha, String remoteSha) {
    MergeOutcome outcome = mirror.previewMerge(localSha, remoteSha);
    if (outcome.clean()) {
      String message = "Merge remote '" + branch + "' into " + branch;
      String mergeSha =
          mirror.commitTree(
              outcome.treeSha(),
              List.of(localSha, remoteSha),
              message,
              gitIdentity.asCommitIdentity());
      return new MergeBuild(
          mergeSha, "Merged remote into '" + branch + "' (merge commit " + shortSha(mergeSha) + ")");
    }
    String mergeBranch = mergeBranchName(branch);
    parkConflictingRemoteTip(mirror, branch, remoteSha);
    throw new BadRequestException(
        "Branch '"
            + branch
            + "' conflicts with the remote (conflicting files: "
            + String.join(", ", outcome.conflictedPaths())
            + "); the remote tip was saved to branch '"
            + mergeBranch
            + "' (replacing any previous attempt) — merge '"
            + branch
            + "' into it, resolve the conflicts, integrate it back, then pull, sync or push"
            + " again");
  }

  /**
   * Parks {@code remoteSha} on {@code merge/<branch>-origin-<branch>}, replacing whatever a previous
   * attempt left there.
   *
   * <p>This used to be a force-push ({@code +<sha>:refs/heads/…}) and cannot be one any more: {@code
   * gitmirror} has no force flag by design, so every push it makes is a compare-and-swap. Delete
   * then recreate is the two-step equivalent — a deletion is never subject to a fast-forward check,
   * only an update is — and it is safe precisely here: this branch is a disposable scratch pointer
   * offered as a coordination convenience, never protected history. A ref anyone else's work depends
   * on would not be replaced this way.
   */
  private void parkConflictingRemoteTip(RepoMirror mirror, String branch, String remoteSha) {
    String mergeBranch = mergeBranchName(branch);
    eu.wohlben.qits.projects.gitmirror.PushSpec park =
        withHostToken(
            eu.wohlben.qits.projects.gitmirror.PushSpec.of(
                eu.wohlben.qits.projects.gitmirror.PushSpec.Ref.branch(remoteSha, mergeBranch)));
    PushOutcome parked = mirror.push(park);
    if (parked.accepted()) {
      return;
    }
    if (!parked.saysNotFastForward()) {
      throw new InternalServerErrorException(
          "Could not park the remote tip on '" + mergeBranch + "': " + parked.output());
    }
    // A previous attempt is in the way. Its outcome is deliberately ignored: no such branch is also
    // a rejection, and that is the state we want anyway.
    mirror.deleteBranch(mergeBranch);
    PushOutcome retried = mirror.push(park);
    if (!retried.accepted()) {
      throw new InternalServerErrorException(
          "Could not park the remote tip on '" + mergeBranch + "': " + retried.output());
    }
  }

  /**
   * A segment name for {@code base} unique within {@code usedSegments} (registering it): the plain
   * base, or {@code base (2)}, {@code base (3)}, … when two repos in one walk share a relative
   * path.
   */
  private static String allocateSegment(String base, Set<String> usedSegments) {
    String name = base;
    for (int n = 2; !usedSegments.add(name); n++) {
      name = base + " (" + n + ")";
    }
    return name;
  }

  private static void settleOk(TechnicalProcess process, String segmentName) {
    if (process != null && segmentName != null) {
      process.settleSegment(segmentName, true);
    }
  }

  private static void streamLine(TechnicalProcess process, String segmentName, String line) {
    if (process != null && segmentName != null) {
      process.appendLine(segmentName, line);
    }
  }

  /**
   * A per-line tap that appends each line to the segment as a git command emits it (for {@link
   * GitExecutor#exec(java.io.File, Consumer, String...)}), or {@code null} when there is no process
   * to stream into — so the synchronous callers keep the plain blocking exec.
   */
  private static Consumer<String> lineSink(TechnicalProcess process, String segmentName) {
    return (process == null || segmentName == null)
        ? null
        : line -> process.appendLine(segmentName, line);
  }

  /**
   * Splits captured git output into lines and appends them to the segment (a no-op sans process).
   */
  private static void streamLines(TechnicalProcess process, String segmentName, String output) {
    if (process == null || segmentName == null || output == null || output.isBlank()) {
      return;
    }
    // Default limit drops trailing empty lines (git output usually ends in a newline) while keeping
    // interior blank lines, so a fetch blob doesn't add a stray blank line to the segment.
    for (String line : output.split("\n")) {
      process.appendLine(segmentName, line);
    }
  }

  /**
   * The scalar snapshot a push needs (url, main branch), read in one short transaction — shared by
   * {@link #pushRepository} and the remote-login sign-in terminal, whose interactive push runs
   * exactly the same command shape in a host-side PTY. No path (projects-volume-decoupling-plan.md
   * §3.5): every reader gets the mirror's git dir from {@link GitMirrorRegistry} itself, by repo id.
   */
  public record PushSpec(String url, String branch) {}

  /** Reads a {@link PushSpec} in its own short transaction (404 for an unknown id). */
  public PushSpec pushSpec(String repoId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Repository repo = get(repoId);
              // The 404 guard runs here rather than after the caller's refreshNow(), which would
              // clone a missing mirror from the host — see requireExistingOrigin.
              Path originPath = requireExistingOrigin(repoId);
              return new PushSpec(repo.url, resolveMainBranch(repo, originPath));
            });
  }

  /**
   * Pushes the local main branch to the remote. Pushes to the URL directly rather than the "origin"
   * remote, whose {@code mirror=true} config forbids the single-branch refspec.
   *
   * <p>A push the remote rejects as non-fast-forward (the remote gained commits we don't have)
   * doesn't just fail anymore: the remote branch is fetched and reconciled with the same policy the
   * pull uses — remote strictly ahead fast-forwards the git host (nothing to push), a diverged
   * branch that merges cleanly gets a merge commit and the push is retried once with it, and a
   * conflicting divergence parks the remote tip on {@code merge/<branch>-origin-<branch>} (see
   * {@link #buildDivergedMerge}) and fails with the resolution path in the message.
   *
   * <p>Reads its inputs in a short transaction and runs {@code git push} outside it (the pull's
   * {@link PullContext} pattern), so it is safe on a worker thread with no request context — the
   * streamed sync ({@link #beginSyncRepository}) calls it there.
   */
  public String pushRepository(String repoId) {
    requireMembership(repoId);
    PushSpec ctx = pushSpec(repoId);
    // Nothing to push TO: a greenfield wrapper has no backup remote until one is attached. Report
    // it
    // rather than failing — the remote is a backup, so its absence is a configuration state.
    if (ctx.url() == null || ctx.url().isBlank()) {
      return "No backup remote configured — nothing to push";
    }
    RepoMirror mirror = gitMirrors.of(repoId);
    try {
      // The "about to write" rule: what reaches the forge must be what the git host holds, so the
      // mirror is caught up first and the branch pushed below is the host's own tip.
      mirror.refreshNow();
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Git push failed: " + e.getMessage());
    }
    try {
      return push(mirror, ctx);
    } catch (Exception e) {
      if (!isNonFastForwardRejection(e.getMessage())) {
        throw new InternalServerErrorException("Git push failed: " + e.getMessage());
      }
      return reconcileRejectedPush(repoId, mirror, ctx, e.getMessage());
    }
  }

  /** The push to the <b>forge</b> — the row's own remote, with the row's own credentials. */
  private String push(RepoMirror mirror, PushSpec ctx) throws Exception {
    return git.exec(
        mirror.gitDir().toFile(),
        remoteAuth.gitWithCredentials(
            "push", ctx.url(), "refs/heads/" + ctx.branch() + ":refs/heads/" + ctx.branch()));
  }

  /**
   * The remote refused the ref update because it holds commits the mirror doesn't — git's "fetch
   * first"/"non-fast-forward" rejection, as opposed to a hook decline ("remote rejected") or a
   * transport/auth failure, which must surface unchanged.
   */
  private static boolean isNonFastForwardRejection(String message) {
    return message != null
        && (message.contains("non-fast-forward") || message.contains("fetch first"));
  }

  /**
   * The push half of the divergence policy: fetch the remote branch, then fast-forward the mirror
   * when the remote is strictly ahead (nothing local to push), merge-and-retry-once when the
   * histories diverged but merge cleanly, and let {@link #mergeDivergedRemote}'s conflict path park
   * the remote tip and throw otherwise. When the fetched tip turns out NOT to explain the rejection
   * (already contained locally — e.g. a racing pull got there first), the original rejection is
   * surfaced unchanged.
   */
  private String reconcileRejectedPush(
      String repoId, RepoMirror mirror, PushSpec ctx, String rejection) {
    try {
      mirror.fetchIntoFetchHead(ctx.url(), "refs/heads/" + ctx.branch(), remoteAuth);
      Optional<String> remoteOpt = mirror.resolve("FETCH_HEAD");
      Optional<String> localOpt = mirror.resolve("refs/heads/" + ctx.branch());
      if (remoteOpt.isEmpty() || localOpt.isEmpty()) {
        throw new InternalServerErrorException("Git push failed: " + rejection);
      }
      String remoteSha = remoteOpt.get();
      String localSha = localOpt.get();
      if (remoteSha.equals(localSha) || mirror.isAncestor(remoteSha, localSha)) {
        // The fetched tip does not explain the rejection (already contained locally — e.g. a racing
        // pull got there first): surface the original rejection unchanged.
        throw new InternalServerErrorException("Git push failed: " + rejection);
      }
      if (mirror.isAncestor(localSha, remoteSha)) {
        // The forge simply moved ahead — catch the git host up instead of failing. This is a push
        // to the HOST, so the commits arrive through receive-pack like any other.
        requireAccepted(
            mirror.push(
                withHostToken(
                    eu.wohlben.qits.projects.gitmirror.PushSpec.of(
                        eu.wohlben.qits.projects.gitmirror.PushSpec.Ref.branch(
                            remoteSha, ctx.branch())))),
            "Git push failed");
        return "Remote is ahead; fast-forwarded '"
            + ctx.branch()
            + "' to "
            + shortSha(remoteSha)
            + " — nothing to push";
      }
      MergeBuild merge = buildDivergedMerge(mirror, ctx.branch(), localSha, remoteSha);
      // Retry the FORGE push with the merge commit itself. It needs no ref of its own and no trip
      // through the host: its only job here is satisfying the forge's fast-forward check, and a
      // later pull reconciles the forge's content into the host if anyone cares.
      String pushOutput =
          git.exec(
              mirror.gitDir().toFile(),
              remoteAuth.gitWithCredentials(
                  "push", ctx.url(), merge.mergeSha() + ":refs/heads/" + ctx.branch()));
      return merge.verdict() + "\n" + pushOutput;
    } catch (BadRequestException | InternalServerErrorException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git push failed: " + e.getMessage());
    }
  }

  /**
   * Pull then push the main branch, synchronously. Kept as the throwing, request-thread variant for
   * internal callers; the browser-facing sync endpoint uses the streamed {@link
   * #beginSyncRepository}.
   */
  public String syncRepository(String repoId) {
    String pullOutput = pullRepository(repoId);
    String pushOutput = pushRepository(repoId);
    return (pullOutput + "\n" + pushOutput).trim();
  }

  /**
   * The streamed sync: the {@link #beginPullRepository} walk (one {@code pull:<repo>} segment per
   * repository) followed by a single {@code push:<basename>} segment wrapping {@link
   * #pushRepository}. Registers the {@link TechnicalProcess} before any git runs and returns its id
   * immediately; the browser watches pull-then-push over SSE. Throws 404 in-request for an unknown
   * id.
   *
   * <p>Failure semantics carry over: a diverged/unreachable pull fails the process before the push
   * segment opens ({@link TechnicalProcess#failProvision}); a push failure settles only the {@code
   * push} segment {@code failed} and lets {@code finish()} compute overall {@code done failed}, so
   * a green pull with a red push reads exactly that way.
   *
   * <p>Kind-aware single-flight (see {@link #beginPullRepository}): a live sync is reused; a live
   * <em>pull</em> is a conflict (attaching a sync to a pull would silently skip the push), rejected
   * with a 400 rather than letting a sync report success without pushing.
   */
  public String beginSyncRepository(String repoId) {
    // Validate in-request (unknown id → plain 404, not a process) and derive the url basename
    // shared
    // by the root pull segment and the final push segment.
    String basename =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  requireWrapperMembership(get(repoId));
                  return repoLabel(get(repoId));
                });
    return switch (beginForRepository(repoId, "sync")) {
      case RepoProcessLease.Reused r -> r.processId();
      case RepoProcessLease.Conflict c -> throw repositoryBusy(c.runningKind());
      case RepoProcessLease.Fresh f -> {
        TechnicalProcess process = f.process();
        String rootSegment = "pull:" + basename;
        // Segment names double as the segment key: seed the allocator with the root name so the
        // push
        // segment (and any child pull) can't collide with it.
        Set<String> usedSegments = new HashSet<>();
        usedSegments.add(rootSegment);
        processExecutor.submit(
            () -> {
              try {
                pullRepository(repoId, new HashSet<>(), process, rootSegment, usedSegments);
                // Pull walk done — append the push as its own segment, opened before the push so
                // "now pushing" is visible while it blocks on the network.
                String pushSegment = allocateSegment("push:" + basename, usedSegments);
                process.openSegment(pushSegment);
                try {
                  streamLines(process, pushSegment, pushRepository(repoId));
                  settleOk(process, pushSegment);
                } catch (RuntimeException e) {
                  // A push failure degrades this segment only (not failProvision): the pull
                  // segments
                  // stay green and finish() computes overall `done failed` from the red push
                  // segment. WARN so a host refusal (or anything else) is never silent — see the
                  // outer catch below for why this must not be debug.
                  process.appendLine(pushSegment, "push failed: " + e.getMessage());
                  settleWithAuthHint(process, pushSegment, e.getMessage(), repoId);
                  LOG.warnf(e, "Streamed sync's push segment failed for repository %s", repoId);
                }
                process.expectServices(List.of());
                process.finishProvision(true);
              } catch (RuntimeException e) {
                // Root pull failure (diverged branch, unreachable remote, a host push refused):
                // settle the open pull segment failed and emit `done failed` before the push
                // segment ever opens. Idempotent. WARN, not debug — see beginPullRepository's catch
                // for why.
                failWithAuthHint(process, e.getMessage(), repoId);
                LOG.warnf(e, "Streamed sync failed for repository %s", repoId);
              }
            });
        yield process.id();
      }
    };
  }

  /**
   * The streamed push: a single {@code push:<basename>} segment wrapping {@link #pushRepository} —
   * {@link #beginSyncRepository} minus the pull walk. Registers the {@link TechnicalProcess} before
   * the push runs and returns its id immediately; a push failure settles the segment {@code failed}
   * with git's message in-stream and {@code finish()} computes overall {@code done failed}. Throws
   * 404 in-request for an unknown id.
   *
   * <p>Kind-aware single-flight (see {@link #beginPullRepository}): a live push is reused; a live
   * pull/sync is a conflict (400), and vice versa — one repo process at a time.
   */
  public String beginPushRepository(String repoId) {
    // Validate in-request (unknown id → plain 404, not a process) and name the sole segment by the
    // repo's url basename, matching the sync's push segment shape.
    String rootSegment =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  requireWrapperMembership(get(repoId));
                  return "push:" + repoLabel(get(repoId));
                });
    return switch (beginForRepository(repoId, "push")) {
      case RepoProcessLease.Reused r -> r.processId();
      case RepoProcessLease.Conflict c -> throw repositoryBusy(c.runningKind());
      case RepoProcessLease.Fresh f -> {
        TechnicalProcess process = f.process();
        processExecutor.submit(
            () -> {
              try {
                // Open before the push so "now pushing" is visible while it blocks on the network.
                process.openSegment(rootSegment);
                try {
                  streamLines(process, rootSegment, pushRepository(repoId));
                  settleOk(process, rootSegment);
                } catch (RuntimeException e) {
                  // Degrade the segment only (not failProvision): the red segment carries git's
                  // full message and finish() computes overall `done failed` from it. WARN so a
                  // host refusal is never silent — see beginPullRepository's catch for why.
                  process.appendLine(rootSegment, "push failed: " + e.getMessage());
                  settleWithAuthHint(process, rootSegment, e.getMessage(), repoId);
                  LOG.warnf(e, "Streamed push failed for repository %s", repoId);
                }
                process.expectServices(List.of());
                process.finishProvision(true);
              } catch (RuntimeException e) {
                process.failProvision(e.getMessage());
                LOG.warnf(e, "Streamed push failed for repository %s", repoId);
              }
            });
        yield process.id();
      }
    };
  }

  /**
   * SEAM (migration-plan.md §6): {@link TechnicalProcessRegistry#beginForRepository} through the
   * optional port. With no implementation wired in there is nothing to narrate to and nothing to
   * single-flight against, so every call is {@link RepoProcessLease.Fresh} over an
   * {@link #UNTRACKED} process — the walk runs exactly as before, on the same worker thread, and
   * the begin* method returns a null process id. Every narration call site in this class is already
   * null-guarded for the non-streamed {@code pullRepository(repoId)} overload, so the two paths are
   * behaviourally identical apart from the (absent) stream.
   */
  private RepoProcessLease beginForRepository(String repoId, String kind) {
    return processes.isUnsatisfied()
        ? new RepoProcessLease.Fresh(UNTRACKED)
        : processes.get().beginForRepository(repoId, kind);
  }

  /** A {@link TechnicalProcess} that narrates nowhere and has no id. */
  private static final TechnicalProcess UNTRACKED =
      new TechnicalProcess() {
        @Override
        public String id() {
          return null;
        }

        @Override
        public boolean isTerminal() {
          return true;
        }

        @Override
        public void attach(Listener listener) {}

        @Override
        public void detach(Listener listener) {}

        @Override
        public void openSegment(String name) {}

        @Override
        public void appendLine(String segmentName, String line) {}

        @Override
        public boolean isSegmentSettled(String segmentName) {
          return false;
        }

        @Override
        public void settleSegment(String segmentName, boolean ok) {}

        @Override
        public void settleSegment(String segmentName, boolean ok, String hint, String hintTarget) {}

        @Override
        public void completeNoOp(String segmentName, String note) {}

        @Override
        public void expectServices(java.util.Collection<String> serviceNames) {}

        @Override
        public void finishProvision(boolean ok) {}

        @Override
        public void failProvision(String message) {}

        @Override
        public void failProvision(String message, String hint, String hintTarget) {}

        @Override
        public void forceFinish() {}
      };

  /** Sets the branch this repository syncs with the remote. The branch must exist locally. */
  @Transactional
  public Repository setMainBranch(String repoId, String branch) {
    Repository repo = get(repoId);
    if (branch == null || branch.isBlank()) {
      throw new BadRequestException("branch is required");
    }
    if (!listBranches(repoId).contains(branch)) {
      throw new BadRequestException("Unknown branch: " + branch);
    }
    repo.mainBranch = branch;
    return repo;
  }

  /**
   * Reports how far the main branch is ahead of / behind the remote, using a read-only {@code git
   * ls-remote} (no objects fetched). Degrades gracefully when the remote is unreachable.
   */
  public SyncStatusDto syncStatus(String repoId) {
    Repository repo = get(repoId);
    RepoMirror mirror = requireMirror(repoId);
    String branch = resolveMainBranch(repo, mirror.gitDir());

    // No backup remote configured (a greenfield wrapper): the query itself succeeded, there is just
    // no remote branch to compare against. The UI keys its "configure backup remote" affordance off
    // the repository's null url, not off this DTO.
    if (!hasBackupRemote(repo)) {
      return new SyncStatusDto(branch, true, false, null, null);
    }

    // "Local" means what the git host currently holds, read with ls-remote (§3.5): authoritative and
    // unaffected by the mirror's own freshness window, unlike a rev-parse of the local clone.
    String localSha = mirror.remoteBranchSha(branch).orElse(null);
    if (localSha == null) {
      // The main branch doesn't exist on the git host — treat as nothing to report.
      return new SyncStatusDto(branch, true, false, null, null);
    }

    // By repo.url, not a configured "origin" remote (§3.4): the bare's own remote config is never
    // written any more (see attachBackupRemote), so a configured "origin" is not something every
    // repository still has — a cloned repository's mirror happens to carry one from `git clone
    // --mirror`, a wrapper's never does.
    String remoteSha;
    try {
      String out =
          git.exec(
                  mirror.gitDir().toFile(),
                  remoteAuth.gitWithCredentials("ls-remote", repo.url, "refs/heads/" + branch))
              .trim();
      remoteSha = out.isBlank() ? null : out.split("\\s+")[0];
    } catch (Exception e) {
      return new SyncStatusDto(branch, false, false, null, null);
    }

    if (remoteSha == null) {
      return new SyncStatusDto(branch, true, false, null, null);
    }
    if (remoteSha.equals(localSha)) {
      return new SyncStatusDto(branch, true, true, 0, 0);
    }

    // The histories differ. Counting needs the remote commits in the mirror's object store, so
    // fetch them first. Fetch by URL rather than via the "origin" remote: origin is a --mirror,
    // so `git fetch origin` would fast-forward refs/heads/* (a de-facto pull). Fetching the URL
    // populates the objects and FETCH_HEAD while leaving the mirror's branch refs untouched —
    // the same reason pushRepository talks to the URL instead of the mirror remote.
    Integer ahead = null;
    Integer behind = null;
    try {
      // `--end-of-options` forces the URL and refspec to be read as operands, and the
      // `refs/heads/` prefix means a crafted branch name can never start with `-`, so neither
      // can smuggle a git flag (e.g. `--upload-pack=<cmd>`) into the fetch.
      git.exec(
          mirror.gitDir().toFile(),
          remoteAuth.gitWithCredentials(
              "fetch", "--end-of-options", repo.url, "refs/heads/" + branch));
      String counts =
          git.exec(
                  mirror.gitDir().toFile(),
                  "git",
                  "rev-list",
                  "--left-right",
                  "--count",
                  remoteSha + "..." + localSha)
              .trim();
      String[] parts = counts.split("\\s+");
      if (parts.length == 2) {
        behind = Integer.parseInt(parts[0]);
        ahead = Integer.parseInt(parts[1]);
      }
    } catch (Exception ignored) {
      // Fetch failed or counts unavailable — leave them null (the UI shows "unknown", not in-sync).
    }
    return new SyncStatusDto(branch, true, true, ahead, behind);
  }

  public Repository get(String repoId) {
    return repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
  }

  /**
   * The repository a project addresses by {@code name} — the one resolution wrapper membership
   * honours, in one place so nothing can drift from it.
   *
   * <p>Two steps, and the second is not a fallback for tidiness:
   *
   * <ol>
   *   <li>the {@code (project, name)} alias row, which is what an ordinary repository owns;
   *   <li>the name read as the repository's own <b>id</b>, within this project. Adopted platform
   *       repositories are keyed by their directory name — the bootstrap created the origin before
   *       any row existed — so the name <em>is</em> the id and there is no alias row to find.
   * </ol>
   *
   * <p>Both callers are name resolutions a person can compare: the wrapper block the UI reads
   * ({@link WrapperReconcileService#view}) and the git host's name-addressed scheme behind {@code
   * GET /projects/api/projects/{projectId}/repositories/by-name/{repoName}}. One resolving a
   * repository the other does not would show as drift that is not there.
   *
   * @param name the addressable name, with no {@code .git} suffix — callers reading it off a url or
   *     a path segment normalize first
   */
  public Optional<Repository> findByProjectAndName(String projectId, String name) {
    return repositoryNameRepository
        .findRepositoryByProjectAndName(projectId, name)
        .or(
            () ->
                repositoryRepository
                    .findByIdOptional(name)
                    .filter(repo -> repo.project != null && repo.project.id.equals(projectId)));
  }

  // -----------------------------------------------------------------------------------------
  // wrapper membership — a repository the wrapper does not name is not part of the project
  // -----------------------------------------------------------------------------------------

  /**
   * Refuses a write to a placeable repository that the project's wrapper does not declare.
   *
   * <p>The rule the whole feature rests on is that a project <em>is</em> its wrapper repository, so
   * a repository missing from {@code .gitmodules} is not a component of it — and writing to one
   * (pulling into it, pushing it, deleting its branches) would be operating on something the project
   * has no record of. Reads stay open, because seeing a stray repository is how you find out it is
   * one.
   *
   * <p>Three exemptions, each for its own reason:
   *
   * <ul>
   *   <li>the wrapper itself and every <b>unplaceable</b> archetype ({@code FORK}, {@code
   *       SERVICE_TEMPLATE}) — they have no directory to be mounted under, so membership is not a
   *       question that applies to them;
   *   <li>a project whose wrapper declares <b>no submodules at all</b>. An empty manifest is not a
   *       manifest: a project that has not started declaring members has nothing to be a member of,
   *       and enforcing against it would brick every repository of every project the day this ships.
   *       The first entry is what turns the wrapper into the project's configuration;
   *   <li>a wrapper this service cannot read right now. Refusing a push because a mirror refresh
   *       failed would trade a real operation for a guard's own outage, so the failure is a WARN and
   *       the write goes ahead.
   * </ul>
   */
  private void requireWrapperMembership(Repository repo) {
    if (repo.archetype == null || !repo.archetype.isPlaceable()) {
      return;
    }
    Repository wrapper = repositoryRepository.findWrapperByProject(repo.project.id).orElse(null);
    if (wrapper == null || wrapper.id.equals(repo.id)) {
      return;
    }
    List<WrapperGitmodules.Entry> declared;
    try {
      declared = WrapperGitmodules.entries(wrapperWriter.readGitmodules(wrapper));
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read the wrapper's .gitmodules for project %s (%s) — letting the write through"
              + " rather than failing it on the guard's own outage.",
          repo.project.id, e.getMessage());
      return;
    }
    if (declared.isEmpty()) {
      return;
    }
    Set<String> mine = new HashSet<>(repositoryNameRepository.namesFor(repo));
    mine.add(repo.id);
    for (WrapperGitmodules.Entry entry : declared) {
      if (mine.contains(entry.name()) || mine.contains(basenameOfPath(entry.path()))) {
        return;
      }
    }
    throw new BadRequestException(
        "Repository '"
            + repoLabel(repo)
            + "' is not a submodule of this project's wrapper, so it is not part of the project and"
            + " cannot be written to. Add it to the wrapper's .gitmodules and push, then run"
            + " POST /projects/api/projects/"
            + repo.project.id
            + "/repositories/reconcile.");
  }

  /** {@link #requireWrapperMembership} by id, in its own short transaction. */
  private void requireMembership(String repoId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              requireWrapperMembership(get(repoId));
            });
  }

  /** The last segment of a {@code .gitmodules} path — {@code services/foo} names {@code foo}. */
  private static String basenameOfPath(String path) {
    if (path == null) {
      return "";
    }
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  /**
   * The repository's branches, read live off the git host ({@code ls-remote --heads}) rather than
   * the local mirror (§3.5): authoritative and no clone needed, so this answers correctly even for a
   * repository whose mirror was never cloned.
   */
  public List<String> listBranches(String repoId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    try {
      return gitMirrors.of(repoId).remoteBranches();
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Git branch listing failed: " + e.getMessage());
    }
  }

  /**
   * The repository's branches, each tagged with whether it can be safely cleaned up (see {@link
   * WorkspaceService#canCleanupBranch}). Used by the branch list UI to offer cleanup in place of
   * integrate once a branch is fully merged.
   */
  public List<BranchDto> listBranchesWithCleanup(String repoId) {
    Repository repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    return listBranches(repoId).stream()
        .map(
            b -> {
              WorkspaceLookup lookup = workspaces.isUnsatisfied() ? null : workspaces.get();
              var summary =
                  lookup == null
                      ? new WorkspaceLookup.BranchSummary(null, null, null)
                      : lookup.summarize(repoId, b, repo.mainBranch);
              return new BranchDto(
                  b,
                  lookup != null && lookup.canCleanupBranch(repoId, b, repo.mainBranch),
                  summary.parent(),
                  summary.ahead(),
                  summary.behind());
            })
        .toList();
  }

  /**
   * Deletes a git branch from the repository's origin. Refuses to delete a branch that is the
   * {@code parent} of any workspace, since that would orphan those workspaces in the branch tree.
   *
   * <p>Pushed as a deletion (projects-volume-decoupling-plan.md §3.7) rather than run as a local
   * {@code git branch -D}: with {@link #listBranches} now reading the git host directly (§3.5), a
   * local-only delete would silently un-delete itself the moment anything re-reads the branch list.
   * The push passes through the git host's {@code ProtectedRefHook}, so deleting a repository's
   * default branch is refused there — and that refusal surfaces as a 4xx carrying the hook's own
   * message rather than a 500, since it is a statement about the request.
   */
  @Transactional
  public void deleteBranch(String repoId, String branch) {
    requireWrapperMembership(get(repoId));

    // `branch` is user-supplied: reject blank or dash-leading names so a value like
    // "-D"/"--force" can't be smuggled to git as a flag (argv flag injection).
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name: " + branch);
    }

    boolean hasChildren =
        !workspaces.isUnsatisfied()
            && workspaces.get().findActiveByRepository(repoId).stream()
                .anyMatch(wt -> branch.equals(wt.parent()));
    if (hasChildren) {
      throw new BadRequestException("Branch has child workspaces: " + branch);
    }

    requireExistingOrigin(repoId); // 404 for a repository with no mirror at all

    try {
      PushOutcome outcome = gitMirrors.of(repoId).deleteBranch(branch);
      if (!outcome.accepted()) {
        // The delete goes through the git host's ProtectedRefHook, which guards the repository's
        // HEAD. Its refusal is a statement about the request, not a fault here, so it surfaces as a
        // 4xx carrying the hook's own words — the same rule qits-workspaces settled for
        // PUSH_REJECTED. Anything receive-pack did not phrase as a refusal stays a 500.
        String refusal = outcome.remoteRefusal();
        if (refusal != null) {
          throw new BadRequestException(
              "The git host refused to delete '" + branch + "': " + refusal);
        }
        throw new InternalServerErrorException("Git branch delete failed: " + outcome.output());
      }
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Git branch delete failed: " + e.getMessage());
    }
  }

  /**
   * Deletes a repository, <b>refusing the project's wrapper</b>: the wrapper is the project root
   * and goes with the project, not on its own. {@code ProjectService.delete} tears it down through
   * {@link #deleteInternal}.
   *
   * <p>A member of the wrapper leaves the wrapper first, in its own commit and push. That ordering
   * is the point: a {@code .gitmodules} entry pointing at a repository nobody serves any more breaks
   * every clone of the project, whereas an entry removed a moment before the teardown is simply the
   * project having one component fewer.
   *
   * <p>Not {@code @Transactional}: the wrapper commit is network work, and holding a database
   * transaction open across a push to another service is how a slow host becomes a lock timeout.
   * {@link #deleteInternal} owns the row transaction.
   */
  public void delete(String repoId) {
    Repository repo = QuarkusTransaction.requiringNew().call(() -> get(repoId));
    if (repo.archetype == RepositoryArchetype.PROJECT) {
      throw new BadRequestException(
          "This is the project's wrapper repository — the project root — and cannot be deleted on"
              + " its own; delete the project instead.");
    }
    removeFromWrapper(repo);
    deleteInternal(repoId);
  }

  /**
   * Takes {@code repo} out of its project's wrapper, if it is in it. Every name the repository
   * answers to is tried, because the wrapper records one of them and this service does not get to
   * assume which.
   */
  private void removeFromWrapper(Repository repo) {
    if (repo.archetype == null || !repo.archetype.isPlaceable()) {
      return;
    }
    Repository wrapper =
        QuarkusTransaction.requiringNew()
            .call(() -> repositoryRepository.findWrapperByProject(repo.project.id).orElse(null));
    if (wrapper == null || wrapper.id.equals(repo.id)) {
      return;
    }
    List<String> names =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<String> all =
                      new ArrayList<>(repositoryNameRepository.namesFor(get(repo.id)));
                  all.add(repo.id);
                  return all;
                });
    for (String name : names) {
      if (wrapperWriter.removeFromWrapper(wrapper, name).isPresent()) {
        return;
      }
    }
  }

  /**
   * Drops the row for a repository the wrapper no longer declares, and <b>nothing more</b> — the
   * reconcile's deregistration.
   *
   * <p>Deliberately lighter than {@link #deleteInternal}: the workspaces go, because a workspace on
   * a repository the project has no record of is a container nobody can reach, but the git host's
   * repository and this service's mirror both stay. Deregistering is a statement about membership,
   * not about the history — put the entry back in the wrapper and the next reconcile adopts the very
   * same repository again, with everything still there.
   */
  @Transactional
  public void deregisterRow(String repoId) {
    Repository repo = get(repoId);
    if (!workspaceLifecycle.isUnsatisfied()) {
      try {
        workspaceLifecycle.get().releaseRepository(repoId);
      } catch (RuntimeException e) {
        LOG.warnf(
            "Workspace teardown failed while deregistering repository %s: %s",
            repoId, e.getMessage());
      }
    }
    repositoryRepository.delete(repo);
  }

  /**
   * {@link #delete} without the wrapper guard — the path a project deletion takes.
   *
   * <p><b>The git host's copy is not touched (⚖2).</b> There is no delete verb on the host — see
   * {@code GitHostRepositories}' javadoc — so this removes the row, the repository's workspaces and
   * its local mirror cache, and leaves the history the host holds exactly where it is. A repository
   * deleted here and re-created (or re-adopted) at the same id finds that history still there; a
   * mirror is a cache and deleting it costs nothing worse than a re-clone on next use.
   */
  @Transactional
  public void deleteInternal(String repoId) {
    Repository repo = get(repoId);
    // Delete the whole footprint, not just the DB row: otherwise every delete (and every seed
    // reset, which deletes then recreates) leaks the repo's workspace containers, their persistent
    // /workspace volumes, and its local mirror as orphans. DB rows for workspaces/commands/events/
    // services cascade off the repository row deletion below.
    // SEAM (migration-plan.md §6, repository <-> workspace). Was an inline docker teardown of this
    // repository's workspace containers and their persistent /workspace volumes (containers first —
    // docker refuses an in-use volume). ContainerRuntime/DockerExecutor are qits-workspaces'. The
    // ordering is a precondition of the delete, so it stays a synchronous call, now through a port.
    if (!workspaceLifecycle.isUnsatisfied()) {
      try {
        workspaceLifecycle.get().releaseRepository(repoId);
      } catch (RuntimeException e) {
        LOG.warnf("Workspace teardown failed while deleting repository %s: %s", repoId, e.getMessage());
      }
    }
    // An adopted repository (see adoptExistingOrigin) never had a mirror cloned, so this is a no-op
    // for one — deleteRecursively already tolerates a path that does not exist.
    deleteRecursively(gitMirrors.of(repoId).gitDir());
    // The rows referencing this repository (workspaces and their events, name aliases, commands)
    // go by the schema's `on delete cascade`, not one by one here. That is correct as long as each
    // service call owns its transaction, which every caller does. A caller that instead CREATED
    // this repository earlier in the SAME transaction would still hold those children managed, and
    // Hibernate would flush a child pointing at a removed parent — so don't do that; give the
    // create and the delete their own transactions, as production always does.
    repositoryRepository.delete(repo);
  }

  /** Best-effort recursive delete — children before parents. */
  private void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  LOG.warnf("Failed to delete %s: %s", p, e.getMessage());
                }
              });
    } catch (IOException e) {
      LOG.warnf("Failed to remove directory %s: %s", dir, e.getMessage());
    }
  }
}
