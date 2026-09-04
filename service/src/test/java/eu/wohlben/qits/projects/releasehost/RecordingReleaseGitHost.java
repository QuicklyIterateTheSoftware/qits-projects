package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseGitHost;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link ReleaseGitHost}: a git host that is a map. An ordinary bean over the {@code
 * @DefaultBean} HTTP adapter, so it wins the injection simply by existing and no test reaches a git
 * host; state through <b>methods</b>, the package convention (the injected reference is a CDI client
 * proxy).
 *
 * <p><b>It is a real little repository, not a stub that says yes.</b> The tree is a path → content
 * map, a commit rewrites those entries and mints a fresh sha, and a tag is a name in a set — so the
 * happy path really reads the manifests it bumps, really commits the bytes the bump produced, and
 * the assertions can be about <em>what the poms say afterwards</em> rather than about which methods
 * were called. That is what makes the tag-exists retry arm meaningful: the second attempt has to
 * re-read the tree the first attempt's commit left behind.
 */
@ApplicationScoped
public class RecordingReleaseGitHost implements ReleaseGitHost {

  /** One commit as it was asked for. */
  public record Commit(String ref, String message, Map<String, String> files, String sha) {}

  /** One tag as it was asked for, and what the host said. */
  public record Tag(String name, String sha, String message, TagResult result) {}

  /** The tree, keyed by the sha it belongs to. */
  private final Map<String, Map<String, String>> trees =
      Collections.synchronizedMap(new LinkedHashMap<>());

  private final List<Commit> commits = Collections.synchronizedList(new ArrayList<>());
  private final List<Tag> tags = Collections.synchronizedList(new ArrayList<>());
  private final List<String> deletedBranches = Collections.synchronizedList(new ArrayList<>());

  /** Tag names the host already holds — the version-uniqueness refusal, staged. */
  private final List<String> taken = Collections.synchronizedList(new ArrayList<>());

  private final AtomicInteger commitCounter = new AtomicInteger();

  /** How many more tags answer {@code tag-exists} whatever they are named. */
  private final AtomicInteger tagCollisions = new AtomicInteger();

  /** Scripted failures, each replacing the ordinary answer of one verb. */
  private final AtomicReference<Answer<List<String>>> treeFailure = new AtomicReference<>();

  private final AtomicReference<Answer<String>> commitFailure = new AtomicReference<>();

  private final AtomicReference<TagAnswer> tagFailure = new AtomicReference<>();

  // ---------------------------------------------------------------------------------------------
  // Staging
  // ---------------------------------------------------------------------------------------------

  /** Put a tree at a sha — what a release will read its manifests out of. */
  public void tree(String sha, Map<String, String> files) {
    trees.put(sha, new LinkedHashMap<>(files));
  }

  /** A tag name the host already holds, so the next attempt at it answers {@code tag-exists}. */
  public void alreadyTagged(String name) {
    taken.add(name);
  }

  /**
   * Refuse the next {@code times} tags as already existing, whatever they are named.
   *
   * <p>Staged by count rather than by name because the caller cannot know the name: the version is
   * stamped from the clock inside the attempt. That is the same reason the real collision is
   * reachable at all — two releases in one second stamp one string.
   */
  public void refuseTagsAsExisting(int times) {
    tagCollisions.set(times);
  }

  public void failTreeWith(Answer<List<String>> answer) {
    treeFailure.set(answer);
  }

  public void failCommitWith(Answer<String> answer) {
    commitFailure.set(answer);
  }

  public void failTagWith(TagAnswer answer) {
    tagFailure.set(answer);
  }

  public void reset() {
    trees.clear();
    commits.clear();
    tags.clear();
    deletedBranches.clear();
    taken.clear();
    commitCounter.set(0);
    tagCollisions.set(0);
    treeFailure.set(null);
    commitFailure.set(null);
    tagFailure.set(null);
  }

  // ---------------------------------------------------------------------------------------------
  // Reading back
  // ---------------------------------------------------------------------------------------------

  public List<Commit> commits() {
    return List.copyOf(commits);
  }

  public List<Tag> tags() {
    return List.copyOf(tags);
  }

  /** Only the tags that were actually created — the released versions. */
  public List<String> createdTags() {
    return tags().stream()
        .filter(tag -> tag.result() == TagResult.CREATED)
        .map(Tag::name)
        .toList();
  }

  public List<String> deletedBranches() {
    return List.copyOf(deletedBranches);
  }

  /** The tree at a sha, as it stands — a commit's effect, read back. */
  public Map<String, String> treeAt(String sha) {
    Map<String, String> tree = trees.get(sha);
    return tree == null ? Map.of() : Map.copyOf(tree);
  }

  // ---------------------------------------------------------------------------------------------
  // The port
  // ---------------------------------------------------------------------------------------------

  @Override
  public Answer<List<String>> tree(String repoId, String rev) {
    Answer<List<String>> failure = treeFailure.get();
    if (failure != null) {
      return failure;
    }
    Map<String, String> tree = trees.get(rev);
    return tree == null
        ? Answer.failed("no-such-rev: " + rev)
        : Answer.of(List.copyOf(tree.keySet()));
  }

  @Override
  public Answer<String> file(String repoId, String rev, String path) {
    Map<String, String> tree = trees.get(rev);
    if (tree == null) {
      return Answer.failed("no-such-rev: " + rev);
    }
    String content = tree.get(path);
    return content == null ? Answer.failed("no-such-path: " + path) : Answer.of(content);
  }

  @Override
  public Answer<String> commit(
      String repoId, String ref, String message, Map<String, String> files) {
    Answer<String> failure = commitFailure.get();
    if (failure != null) {
      return failure;
    }
    // The tip a commit lands on is the newest tree this fake holds, which is what the executor's
    // own sequencing produces: it reads a tree, bumps it and commits onto the branch that tree is.
    String parent = newestSha();
    Map<String, String> tree = new LinkedHashMap<>(trees.getOrDefault(parent, Map.of()));
    tree.putAll(files);
    String sha = "bumped-" + commitCounter.incrementAndGet();
    trees.put(sha, tree);
    commits.add(new Commit(ref, message, Map.copyOf(files), sha));
    return Answer.of(sha);
  }

  @Override
  public TagAnswer tag(String repoId, String name, String sha, String message) {
    TagAnswer failure = tagFailure.get();
    if (failure != null) {
      tags.add(new Tag(name, sha, message, TagResult.FAILED));
      return failure;
    }
    if (tagCollisions.get() > 0 || taken.contains(name)) {
      tagCollisions.decrementAndGet();
      tags.add(new Tag(name, sha, message, TagResult.ALREADY_EXISTS));
      return TagAnswer.alreadyExists("existing-tag-object");
    }
    taken.add(name);
    tags.add(new Tag(name, sha, message, TagResult.CREATED));
    return TagAnswer.created("tag-object-of-" + name);
  }

  @Override
  public void deleteBranch(String repoId, String name) {
    deletedBranches.add(name);
  }

  private String newestSha() {
    String newest = null;
    synchronized (trees) {
      for (String sha : trees.keySet()) {
        newest = sha;
      }
    }
    return newest;
  }
}
