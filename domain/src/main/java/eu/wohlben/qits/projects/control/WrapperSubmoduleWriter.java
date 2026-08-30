package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import eu.wohlben.qits.projects.gitmirror.GitMirrorException;
import eu.wohlben.qits.projects.gitmirror.PushOutcome;
import eu.wohlben.qits.projects.gitmirror.PushSpec;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Commits a component into — or out of — its project's wrapper repository.
 *
 * <p>A project <em>is</em> its wrapper, and this is the only writer of that fact: a repository that
 * is not a {@code .gitmodules} entry is not part of the project. Both verbs are one commit on the
 * wrapper's main branch, made with no working tree at all, and published by a push like every other
 * ref this service moves.
 *
 * <p>The commit carries two things and they must agree:
 *
 * <ul>
 *   <li>the amended {@code .gitmodules} blob ({@link WrapperGitmodules}), and
 *   <li>a {@code 160000} gitlink at the entry's path — {@code <directory>/<name>} or {@code
 *       components/<component>/<name>}, see {@link WrapperPath} — pinned to the child's main-branch
 *       head <em>as it is at add time</em>. Nothing here follows the child afterwards; a gitlink
 *       bump is an ordinary commit somebody makes later.
 * </ul>
 *
 * <p><b>The child must already be on the git host.</b> A gitlink records a sha without resolving it,
 * so a wrapper commit naming a commit the host has never seen would push fine and then break every
 * clone that tried to materialize it. Both callers publish the child first.
 *
 * <p>The push carries the host token, because the wrapper's default branch is protected like every
 * other, and it carries no {@code qits.no-ci}: a wrapper commit is a real change to the project and
 * fires CI exactly like a person's push would. Two concurrent creates race for the same branch tip,
 * so a lost fast-forward is retried from a fresh read — three times, which is a race that has to
 * happen three times in a row to fail.
 */
@ApplicationScoped
public class WrapperSubmoduleWriter {

  private static final Logger LOG = Logger.getLogger(WrapperSubmoduleWriter.class);

  /** The file the whole feature is about. */
  static final String GITMODULES = ".gitmodules";

  /** How many lost fast-forward races a single wrapper commit rides out. */
  static final int MAX_ATTEMPTS = 3;

  /** {@code -o qits.token=<value>} — the git host's {@code ProtectedRefHook} bypass option. */
  private static final String TOKEN_OPTION_PREFIX = "qits.token=";

  /** See {@code RepositoryService#pushToken}: one deployment value, presented back by the pusher. */
  @ConfigProperty(name = "qits.repositories.git.push-token")
  Optional<String> pushToken;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitExecutor git;

  @Inject GitIdentity gitIdentity;

  /**
   * Adds {@code name} to {@code wrapper}'s {@code .gitmodules} under {@code archetype}'s directory —
   * the archetype-layout form, kept for callers that state no component.
   */
  public String addToWrapper(
      Repository wrapper, String name, RepositoryArchetype archetype, String childMainHeadSha) {
    return addToWrapper(wrapper, name, archetype, null, childMainHeadSha);
  }

  /**
   * Adds {@code name} to {@code wrapper}'s {@code .gitmodules}, pinned to {@code childMainHeadSha},
   * and pushes the commit.
   *
   * <p><b>The wrapper decides where the entry lands, not this service's preference</b> — which is
   * what lets a project flip layouts without its create button breaking:
   *
   * <ul>
   *   <li>a stated {@code component} always places at {@code components/<component>/<name>}, so
   *       stating one is also how an archetype-layout wrapper starts its flip;
   *   <li>with no component stated, a wrapper that already mounts anything under {@code components/}
   *       places at {@code components/<name>/<name>} — the one-repository component the campaign's
   *       own map is full of;
   *   <li>otherwise the archetype's directory, exactly as before.
   * </ul>
   *
   * <p>Idempotent: an entry that is already exactly this one leaves the wrapper untouched and
   * returns the path, so a retry after a failed request is a no-op rather than a second commit.
   *
   * @param component the component to mount under, or null to let the wrapper's own layout decide
   * @return the path the entry is mounted at
   */
  public String addToWrapper(
      Repository wrapper,
      String name,
      RepositoryArchetype archetype,
      String component,
      String childMainHeadSha) {
    if (archetype == null || !archetype.isPlaceable()) {
      throw new BadRequestException(
          "Archetype " + archetype + " has no directory in the wrapper, so it cannot be a member.");
    }
    if (childMainHeadSha == null || childMainHeadSha.isBlank()) {
      throw new InternalServerErrorException(
          "Cannot add '" + name + "' to the wrapper: it has no published head to pin the gitlink to.");
    }
    String directory = directoryFor(wrapper, name, archetype, component);
    String path = directory + "/" + name;
    commit(
        wrapper,
        content -> WrapperGitmodules.addEntry(content, name, directory),
        List.of(new RepoMirror.Gitlink(path, childMainHeadSha)),
        List.of(),
        "Add " + path + " to the project");
    return path;
  }

  /** See {@link #addToWrapper(Repository, String, RepositoryArchetype, String, String)}. */
  private String directoryFor(
      Repository wrapper, String name, RepositoryArchetype archetype, String component) {
    String stated = component == null || component.isBlank() ? null : component.trim();
    if (stated != null) {
      return WrapperPath.componentDirectory(stated);
    }
    if (WrapperPath.usesComponentLayout(WrapperGitmodules.entries(readGitmodules(wrapper)))) {
      return WrapperPath.componentDirectory(name);
    }
    return archetype.directory();
  }

  /**
   * Removes {@code name} from {@code wrapper}'s {@code .gitmodules}, drops its gitlink, and pushes
   * the commit. Idempotent: a name the wrapper does not carry leaves it untouched.
   *
   * @return the path the entry was mounted at, or empty when there was none
   */
  public Optional<String> removeFromWrapper(Repository wrapper, String name) {
    Optional<String> path =
        WrapperGitmodules.entries(readGitmodules(wrapper)).stream()
            .filter(entry -> name.equals(entry.name()))
            .map(WrapperGitmodules.Entry::path)
            .filter(p -> p != null && !p.isBlank())
            .findFirst();
    if (path.isEmpty()) {
      LOG.debugf("Wrapper %s carries no submodule '%s' — nothing to remove.", wrapper.id, name);
      return Optional.empty();
    }
    commit(
        wrapper,
        content -> WrapperGitmodules.removeEntry(content, name),
        List.of(),
        List.of(path.get()),
        "Remove " + path.get() + " from the project");
    return path;
  }

  /** The wrapper's {@code .gitmodules} at its main branch, or "" when it has none. */
  public String readGitmodules(Repository wrapper) {
    RepoMirror mirror = gitMirrors.of(wrapper.id);
    try {
      mirror.refresh();
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Could not refresh the wrapper's mirror: " + e.getMessage());
    }
    return readGitmodulesAt(mirror, branchOf(wrapper));
  }

  private String readGitmodulesAt(RepoMirror mirror, String branch) {
    try {
      GitExecutor.ExecResult result = git.showFile(mirror.gitDir().toFile(), branch, GITMODULES);
      if (result.exitCode() != 0) {
        return "";
      }
      // GitExecutor joins the lines it read, which drops the file's final newline — and the blob
      // this text is written back as would then differ from the one it came from, making every
      // no-op re-assert a real commit. git writes this file with a trailing newline; put it back.
      String content = result.output();
      return content.isEmpty() || content.endsWith("\n") ? content : content + "\n";
    } catch (Exception e) {
      LOG.warnf(e, "Could not read %s from %s@%s; treating it as absent", GITMODULES, mirror.repoId(), branch);
      return "";
    }
  }

  /** How a {@code .gitmodules} text becomes the next one. */
  @FunctionalInterface
  private interface Amendment {
    String apply(String content);
  }

  /**
   * The whole write: refresh, read the tip, amend, commit the amended tree, push. Retried from a
   * fresh read when the push loses a fast-forward race, since the other writer's entry has to end up
   * in the file this one appends to.
   */
  private void commit(
      Repository wrapper,
      Amendment amendment,
      List<RepoMirror.Gitlink> gitlinks,
      List<String> removals,
      String message) {
    RepoMirror mirror = gitMirrors.of(wrapper.id);
    String branch = branchOf(wrapper);
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        mirror.refreshNow();
      } catch (GitMirrorException e) {
        throw new InternalServerErrorException(
            "Could not refresh the wrapper's mirror: " + e.getMessage());
      }
      String tip =
          mirror
              .resolve("refs/heads/" + branch)
              .orElseThrow(
                  () ->
                      new InternalServerErrorException(
                          "The wrapper repository has no '"
                              + branch
                              + "' branch on the git host, so there is nothing to commit onto."));

      String before = readGitmodulesAt(mirror, branch);
      String after = amendment.apply(before);

      String treeSha;
      String commitSha;
      try {
        treeSha =
            mirror.amendTree(
                tip,
                List.of(
                    new RepoMirror.TreeEntry(
                        GITMODULES, "100644", after.getBytes(StandardCharsets.UTF_8))),
                gitlinks,
                removals);
        // The tree, not the text, is what decides there is nothing to do: an entry already present
        // AND already pinned to this sha amends to the tree that is already there. This is what
        // makes a retried request a no-op instead of a stream of empty commits.
        if (treeSha.equals(mirror.resolve(tip + "^{tree}").orElse(null))) {
          return;
        }
        commitSha =
            mirror.commitTree(treeSha, List.of(tip), message, gitIdentity.asCommitIdentity());
      } catch (GitMirrorException e) {
        throw new InternalServerErrorException("Could not build the wrapper commit: " + e.getMessage());
      }

      PushOutcome outcome =
          mirror.push(withHostToken(PushSpec.of(PushSpec.Ref.branch(commitSha, branch))));
      if (outcome.accepted()) {
        // Pushing a bare sha moves nothing in the mirror, and the next reader of this wrapper (the
        // reconcile, the repositories listing) reads the mirror.
        mirror.refreshNow();
        return;
      }
      if (!lostTheRace(outcome)) {
        String refusal = outcome.remoteRefusal();
        if (refusal != null) {
          throw new BadRequestException("The git host refused the wrapper commit: " + refusal);
        }
        throw new InternalServerErrorException(
            "Could not push the wrapper commit: " + outcome.output());
      }
      lastFailure =
          new InternalServerErrorException(
              "Could not push the wrapper commit after "
                  + MAX_ATTEMPTS
                  + " attempts; another write kept winning the race: "
                  + outcome.output());
      LOG.infof(
          "Wrapper commit on %s lost a fast-forward race (attempt %d/%d) — re-reading and retrying.",
          wrapper.id, attempt, MAX_ATTEMPTS);
    }
    throw lastFailure;
  }

  /**
   * Whether the push was refused because <em>someone else moved the branch</em> — the one refusal
   * worth retrying, because re-reading is exactly what fixes it.
   *
   * <p>Two shapes, and both are real: git rejects the update client-side as a non-fast-forward when
   * it saw the newer tip in the advertisement, and refuses it server-side as a stale old value (or
   * a lock failure) when the ref moved between the advertisement and the ref transaction. A hook
   * declining the push is neither and must never be retried.
   */
  private static boolean lostTheRace(PushOutcome outcome) {
    if (outcome.saysNotFastForward()) {
      return true;
    }
    String refusal = outcome.remoteRefusal();
    if (refusal == null) {
      return false;
    }
    String lower = refusal.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("incorrect old value")
        || lower.contains("lock")
        || lower.contains("stale info");
  }

  private static String branchOf(Repository wrapper) {
    return wrapper.mainBranch == null || wrapper.mainBranch.isBlank() ? "main" : wrapper.mainBranch;
  }

  /** See {@code RepositoryService.withHostToken}: the wrapper's default branch is protected too. */
  private PushSpec withHostToken(PushSpec spec) {
    return pushToken
        .filter(token -> !token.isBlank())
        .map(token -> spec.withOption(TOKEN_OPTION_PREFIX + token))
        .orElse(spec);
  }
}
