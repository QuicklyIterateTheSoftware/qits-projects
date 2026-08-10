package eu.wohlben.qits.projects.gitmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mirror's lifecycle and the pushes that replace every ref write this service used to perform
 * on the shared {@code qits-repositories} volume.
 *
 * <p>Every case runs against a real bare and a real {@code git}, with no Quarkus, no database and no
 * network. What it is proving is not "git works" but that the substitution holds: that a fetch
 * really does bring the host's refs, that {@code ls-remote} answers a question a stale mirror would
 * answer wrongly, and that a commit this module manufactures with no working tree at all shows up in
 * the served repository only once it has been <b>pushed</b> — never before.
 */
class RepoMirrorTest {

  private static final CommitIdentity QITS = new CommitIdentity("qits", "qits@local");
  private static final GitCredentials NO_CREDENTIALS = new NoCredentials();

  @TempDir Path tmp;

  private LocalBares remotes;
  private GitMirrors mirrors;
  private Path bare;
  private final String repoId = "repo-under-test";

  @BeforeEach
  void setUp() throws Exception {
    bare = TestBare.create(tmp, "origin");
    remotes = new LocalBares();
    remotes.register(repoId, bare);
    mirrors =
        new GitMirrors(
            new GitCli(), remotes, tmp.resolve("projects-data"), Duration.ofSeconds(60), Duration.ZERO);
  }

  // -----------------------------------------------------------------------------------------
  // the mirror's lifecycle
  // -----------------------------------------------------------------------------------------

  @Test
  void aFirstRefreshClonesTheMirrorAndASecondOneOnlyFetches() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    assertFalse(Files.exists(mirror.gitDir()), "nothing is cloned until something needs objects");

    mirror.refreshNow();

    assertTrue(Files.isDirectory(mirror.gitDir()));
    assertEquals(TestBare.refIn(bare, "main"), mirror.resolve("refs/heads/main").orElseThrow());
    assertEquals(
        TestBare.refIn(bare, "feature"), mirror.resolve("refs/heads/feature").orElseThrow());

    // A commit lands on the host afterwards; the mirror learns it on the next refresh and not
    // before. That "and not before" is the freshness contract every read here depends on.
    TestBare.commitOnBranch(bare, "main", "later.txt", "later\n", "a later commit");
    assertNotEquals(TestBare.refIn(bare, "main"), mirror.resolve("refs/heads/main").orElseThrow());

    mirror.refreshNow();
    assertEquals(TestBare.refIn(bare, "main"), mirror.resolve("refs/heads/main").orElseThrow());
  }

  @Test
  void aFetchPrunesBranchesTheHostNoLongerHas() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    assertTrue(mirror.resolve("refs/heads/feature").isPresent());

    TestBare.output(bare.toFile(), "git", "branch", "-D", "feature");
    mirror.refreshNow();

    assertTrue(
        mirror.resolve("refs/heads/feature").isEmpty(),
        "a mirror that kept deleted branches would report ahead/behind against a ghost");
  }

  @Test
  void theFreshnessWindowIsWhatSkipsAFetchAndAPushClearsIt() throws Exception {
    GitMirrors windowed =
        new GitMirrors(
            new GitCli(),
            remotes,
            tmp.resolve("windowed-data"),
            Duration.ofSeconds(60),
            Duration.ofMinutes(10));
    RepoMirror mirror = windowed.of(repoId);
    mirror.refreshNow();
    int afterClone = remotes.fetchUrlCalls.get();

    mirror.refresh();
    assertEquals(afterClone, remotes.fetchUrlCalls.get(), "inside the window nothing is fetched");

    // A push is the one thing that certainly invalidated the mirror, so it clears the window.
    mirror.createBranch("windowed", "main");
    mirror.refresh();
    assertTrue(remotes.fetchUrlCalls.get() > afterClone, "an accepted push makes the mirror stale");
  }

  @Test
  void twoThreadsRefreshingOneMirrorProduceOneUsableMirror() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Void>> calls =
          List.of(
              () -> {
                mirror.refreshNow();
                return null;
              },
              () -> {
                mirror.refreshNow();
                return null;
              },
              () -> {
                mirror.refreshNow();
                return null;
              },
              () -> {
                mirror.refreshNow();
                return null;
              });
      for (Future<Void> result : pool.invokeAll(calls)) {
        result.get();
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(TestBare.refIn(bare, "main"), mirror.resolve("refs/heads/main").orElseThrow());
  }

  // -----------------------------------------------------------------------------------------
  // wire reads
  // -----------------------------------------------------------------------------------------

  @Test
  void lsRemoteAnswersFromTheHostEvenWhenTheMirrorIsStale() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    TestBare.output(bare.toFile(), "git", "branch", "made-outside", "main");

    assertTrue(
        mirror.remoteHasBranch("made-outside"),
        "existence is decided by the repository of record — a cache saying 'gone' would be wrong");
    assertTrue(mirror.resolve("refs/heads/made-outside").isEmpty(), "and the mirror is still stale");
    assertEquals(TestBare.refIn(bare, "main"), mirror.remoteBranchSha("main").orElseThrow());
    assertTrue(mirror.remoteBranchSha("no-such-branch").isEmpty());
    assertTrue(mirror.remoteBranches().containsAll(List.of("main", "feature", "made-outside")));
  }

  @Test
  void aReadNeverConsumesThePushUrl() {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    mirror.remoteBranches();
    mirror.remoteBranchSha("main");
    assertEquals(
        0,
        remotes.pushUrlCalls.get(),
        "pushUrl is asked once per push, which is the instant a staged race is about");
  }

  // -----------------------------------------------------------------------------------------
  // local reads
  // -----------------------------------------------------------------------------------------

  @Test
  void theLocalReadsAnswerAheadBehindAncestryAndConflicts() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();

    AheadBehind ab = mirror.aheadBehind("refs/heads/main", "refs/heads/feature");
    assertEquals(1, ab.ahead());
    assertEquals(1, ab.behind());
    assertEquals(AheadBehind.UNKNOWN, mirror.aheadBehind("refs/heads/main", "refs/heads/nope"));

    assertFalse(mirror.isAncestor("refs/heads/feature", "refs/heads/main"));
    assertTrue(mirror.isAncestor("refs/heads/main", "refs/heads/main"));

    assertTrue(mirror.previewMerge("refs/heads/main", "refs/heads/feature").clean());

    // A real conflict, reported as an answer with its file list rather than as a failure — this is
    // the shape `mergeDivergedRemote`'s conflict path reads.
    TestBare.commitOnBranch(bare, "main", "shared.txt", "ours\n", "our edit");
    TestBare.commitOnBranch(bare, "feature", "shared.txt", "theirs\n", "their edit");
    mirror.refreshNow();
    MergeOutcome preview = mirror.previewMerge("refs/heads/main", "refs/heads/feature");
    assertFalse(preview.clean());
    assertEquals(List.of("shared.txt"), preview.conflictedPaths());
  }

  // -----------------------------------------------------------------------------------------
  // writes
  // -----------------------------------------------------------------------------------------

  @Test
  void creatingAndDeletingABranchAreBothPushes() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();

    assertTrue(mirror.createBranch("task/one", "main").accepted());
    assertEquals(
        TestBare.refIn(bare, "main"),
        TestBare.refIn(bare, "task/one"),
        "the new branch is in the SERVED repository, put there by receive-pack");

    assertTrue(mirror.deleteBranch("task/one").accepted());
    assertFalse(TestBare.refs(bare).contains("refs/heads/task/one"));
  }

  @Test
  void aPushTheHookRejectsIsAnAnswerNotAnException() throws Exception {
    // The protection hook is qits-githost's job in production; this fixture stands in for its
    // refusal shape by rejecting a delete of the checked-out branch some servers guard the same
    // way — what matters here is only that PushOutcome, not an exception, carries the refusal.
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    // main is HEAD's target in the bare; deleting the branch `HEAD` points at is refused by a
    // vanilla receive-pack with denyDeleteCurrent (the default), which is enough to prove a
    // rejection surfaces as PushOutcome rather than a thrown exception.
    PushOutcome rejected = mirror.deleteBranch("main");
    assertFalse(rejected.accepted());
  }

  // -----------------------------------------------------------------------------------------
  // greenfield init — no upstream at all
  // -----------------------------------------------------------------------------------------

  @Test
  void initEmptyPointsHeadAtTheRequestedBranchWithNoRefsAtAll() throws Exception {
    RepoMirror mirror = mirrors.of("fresh-repo");
    assertFalse(Files.exists(mirror.gitDir()));

    mirror.initEmpty("main");

    assertTrue(Files.isDirectory(mirror.gitDir()));
    assertEquals("refs/heads/main", TestBare.output(mirror.gitDir().toFile(), "git", "symbolic-ref", "HEAD").trim());
    assertTrue(mirror.resolve("refs/heads/main").isEmpty(), "an empty mirror names a branch with no commit yet");
  }

  @Test
  void initEmptyRejectsABlankOrFlagShapedBranchName() {
    RepoMirror mirror = mirrors.of("fresh-repo-2");
    assertThrows(GitMirrorException.class, () -> mirror.initEmpty(""));
    assertThrows(GitMirrorException.class, () -> mirror.initEmpty("-x"));
  }

  // -----------------------------------------------------------------------------------------
  // the backup remote — a forge this module does not control the credentials for
  // -----------------------------------------------------------------------------------------

  @Test
  void cloneFromMirrorsAnUpstreamUsingTheSuppliedCredentials() throws Exception {
    Path upstream = TestBare.create(tmp, "upstream");
    RepoMirror mirror = mirrors.of("imported-repo");

    mirror.cloneFrom(upstream.toAbsolutePath().toString(), NO_CREDENTIALS);

    assertTrue(Files.isDirectory(mirror.gitDir()));
    assertEquals(TestBare.refIn(upstream, "main"), mirror.resolve("refs/heads/main").orElseThrow());
    assertEquals(
        TestBare.refIn(upstream, "feature"), mirror.resolve("refs/heads/feature").orElseThrow());
  }

  @Test
  void cloneFromAnEmptyUpstreamLeavesAMirrorWithNoRefs() throws Exception {
    Path upstream = TestBare.createEmpty(tmp, "empty-upstream");
    RepoMirror mirror = mirrors.of("empty-import");

    mirror.cloneFrom(upstream.toAbsolutePath().toString(), NO_CREDENTIALS);

    assertTrue(Files.isDirectory(mirror.gitDir()));
    assertTrue(mirror.resolve("refs/heads/main").isEmpty());
  }

  @Test
  void fetchIntoFetchHeadWritesNoLocalRefAndOverwritesOnEachCall() throws Exception {
    Path forge = TestBare.create(tmp, "forge");
    RepoMirror mirror = mirrors.of("pulled-repo");
    mirror.cloneFrom(forge.toAbsolutePath().toString(), NO_CREDENTIALS);
    String mainBeforePull = mirror.resolve("refs/heads/main").orElseThrow();

    TestBare.commitOnBranch(forge, "main", "upstream-only.txt", "new\n", "forge-side commit");

    mirror.fetchIntoFetchHead(forge.toAbsolutePath().toString(), "main", NO_CREDENTIALS);

    assertEquals(mainBeforePull, mirror.resolve("refs/heads/main").orElseThrow(), "no local ref moved");
    assertEquals(TestBare.refIn(forge, "main"), mirror.resolve("FETCH_HEAD").orElseThrow());
    assertNotEquals(mainBeforePull, mirror.resolve("FETCH_HEAD").orElseThrow());
  }

  // -----------------------------------------------------------------------------------------
  // the plumbing commit path: hash-object -> write-tree -> commit-tree -> push
  // -----------------------------------------------------------------------------------------

  @Test
  void theSkeletonPathBuildsARootCommitWithNoWorkingTreeAndPushesIt() throws Exception {
    // The host already holds an empty repository (BM's `PUT`, out of scope here) by the time a
    // skeleton is pushed into it — an empty bare stands in for that.
    Path host = TestBare.createEmpty(tmp, "skeleton-host");
    String skeletonRepoId = "skeleton-repo";
    remotes.register(skeletonRepoId, host);
    RepoMirror mirror = mirrors.of(skeletonRepoId);
    mirror.initEmpty("main");

    List<RepoMirror.TreeEntry> entries =
        List.of(
            new RepoMirror.TreeEntry("README.md", "100644", "# hello\n".getBytes()),
            new RepoMirror.TreeEntry("docs/AGENTS.md", "100644", "notes\n".getBytes()),
            new RepoMirror.TreeEntry("CLAUDE.md", "120000", "AGENTS.md".getBytes()));

    String treeSha = mirror.writeTree(entries);
    String commitSha =
        mirror.commitTree(treeSha, List.of(), "Initialize the project template skeleton", QITS);

    assertTrue(mirror.resolve(commitSha).isPresent(), "the commit exists in the mirror's object store");
    assertTrue(TestBare.refs(host).isEmpty(), "nothing reached the host before the push");

    assertTrue(mirror.push(PushSpec.of(PushSpec.Ref.branch(commitSha, "main"))).accepted());

    assertEquals(commitSha, TestBare.refIn(host, "main"), "the root commit landed on the host by a push");
    assertEquals("commit", TestBare.catFile(host, "-t", commitSha));
    assertEquals(
        "",
        TestBare.output(host.toFile(), "git", "log", "--format=%P", "-n", "1", commitSha).trim(),
        "a root commit has no parents");
  }

  @Test
  void aDivergedPullBuildsAMergeCommitFromThePreviewedTreeAndPushesIt() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    String localSha = TestBare.refIn(bare, "main");
    String remoteSha = TestBare.refIn(bare, "feature");

    MergeOutcome preview = mirror.previewMerge("refs/heads/main", "refs/heads/feature");
    assertTrue(preview.clean());

    String mergeSha =
        mirror.commitTree(
            preview.treeSha(), List.of(localSha, remoteSha), "Merge remote 'main' into main", QITS);

    assertTrue(mirror.push(PushSpec.of(PushSpec.Ref.branch(mergeSha, "main"))).accepted());
    assertEquals(mergeSha, TestBare.refIn(bare, "main"));
    assertEquals(
        List.of(mergeSha, localSha, remoteSha),
        List.of(
            TestBare.output(bare.toFile(), "git", "rev-list", "--parents", "-n", "1", "main")
                .trim()
                .split(" ")),
        "one commit, two parents, put there by a push and not by a ref write");
  }

  @Test
  void aConflictedPreviewNeverReachesCommitTree() throws Exception {
    TestBare.commitOnBranch(bare, "main", "shared.txt", "ours\n", "our edit");
    TestBare.commitOnBranch(bare, "feature", "shared.txt", "theirs\n", "their edit");
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    String mainBefore = TestBare.refIn(bare, "main");

    MergeOutcome preview = mirror.previewMerge("refs/heads/main", "refs/heads/feature");
    assertFalse(preview.clean());
    assertEquals(List.of("shared.txt"), preview.conflictedPaths());
    assertEquals(mainBefore, TestBare.refIn(bare, "main"), "nothing was pushed for a conflicted preview");
  }

  // -----------------------------------------------------------------------------------------
  // amendTree — the wrapper commit's plumbing
  // -----------------------------------------------------------------------------------------

  /** The tree entries of a tree-ish, one {@code <mode> <type> <sha>\t<path>} line per entry. */
  private List<String> lsTree(String rev) throws Exception {
    return List.of(
            TestBare.output(bare.toFile(), "git", "ls-tree", "-r", "-t", rev).trim().split("\n"))
        .stream()
        .filter(line -> !line.isBlank())
        .toList();
  }

  @Test
  void amendTreeKeepsEveryEntryItWasNotAskedAbout() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    String base = TestBare.refIn(bare, "main");

    String treeSha =
        mirror.amendTree(
            base,
            List.of(new RepoMirror.TreeEntry(".gitmodules", "100644", "[submodule \"a\"]\n".getBytes())),
            List.of(),
            List.of());
    String commitSha = mirror.commitTree(treeSha, List.of(base), "add .gitmodules", QITS);
    assertTrue(mirror.push(PushSpec.of(PushSpec.Ref.branch(commitSha, "main"))).accepted());

    assertEquals(
        "[submodule \"a\"]",
        TestBare.output(bare.toFile(), "git", "show", "main:.gitmodules").trim());
    // README.md and main.txt came from the base tree untouched — an amendment is not a rewrite.
    assertEquals("# fixture", TestBare.output(bare.toFile(), "git", "show", "main:README.md").trim());
    assertEquals("main side", TestBare.output(bare.toFile(), "git", "show", "main:main.txt").trim());
  }

  /**
   * The property the whole wrapper feature rests on: a {@code 160000} entry records a sha the
   * repository does not hold and never has to resolve.
   */
  @Test
  void amendTreeRecordsAGitlinkForACommitThisRepositoryDoesNotHave() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    String base = TestBare.refIn(bare, "main");
    String foreign = "0123456789012345678901234567890123456789";
    assertEquals(
        1,
        mirror.local("git", "cat-file", "-e", foreign).exitCode(),
        "the sha really is absent from this object store");

    String treeSha =
        mirror.amendTree(
            base, List.of(), List.of(new RepoMirror.Gitlink("services/child", foreign)), List.of());
    String commitSha = mirror.commitTree(treeSha, List.of(base), "mount services/child", QITS);
    assertTrue(mirror.push(PushSpec.of(PushSpec.Ref.branch(commitSha, "main"))).accepted());

    assertTrue(
        lsTree("main").stream()
            .anyMatch(line -> line.startsWith("160000 commit " + foreign) && line.endsWith("services/child")),
        "the gitlink is a 160000 commit entry at its path: " + lsTree("main"));
  }

  @Test
  void amendTreeRemovesBlobsAndGitlinksAndToleratesAPathThatIsNotThere() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    String base = TestBare.refIn(bare, "main");
    String foreign = "0123456789012345678901234567890123456789";

    String withChild =
        mirror.commitTree(
            mirror.amendTree(
                base, List.of(), List.of(new RepoMirror.Gitlink("libs/shared", foreign)), List.of()),
            List.of(base),
            "mount libs/shared",
            QITS);

    String withoutChild =
        mirror.commitTree(
            mirror.amendTree(
                withChild, List.of(), List.of(), List.of("libs/shared", "main.txt", "never-there")),
            List.of(withChild),
            "unmount libs/shared",
            QITS);
    assertTrue(mirror.push(PushSpec.of(PushSpec.Ref.branch(withoutChild, "main"))).accepted());

    List<String> entries = lsTree("main");
    assertTrue(entries.stream().noneMatch(line -> line.contains("libs/shared")), entries.toString());
    assertTrue(entries.stream().noneMatch(line -> line.endsWith("main.txt")), entries.toString());
    assertTrue(entries.stream().anyMatch(line -> line.endsWith("README.md")), entries.toString());
  }

  /** Amending twice with the same content answers the same tree — what makes a retry a no-op. */
  @Test
  void amendTreeIsDeterministicAndLeavesNoScratchBehind() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    String base = TestBare.refIn(bare, "main");
    List<RepoMirror.TreeEntry> blobs =
        List.of(new RepoMirror.TreeEntry("docs/note.md", "100644", "note\n".getBytes()));

    String first = mirror.amendTree(base, blobs, List.of(), List.of());
    String second = mirror.amendTree(base, blobs, List.of(), List.of());

    assertEquals(first, second);
    assertNotEquals(first, TestBare.refIn(bare, "main^{tree}"));
    assertFalse(
        Files.exists(tmp.resolve("projects-data").resolve("skeleton").resolve(repoId)),
        "the scratch index and blobs are removed on every path");
  }

  @Test
  void amendTreeRefusesABaseItCannotRead() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    assertThrows(
        GitMirrorException.class,
        () -> mirror.amendTree("refs/heads/no-such-branch", List.of(), List.of(), List.of()));
  }
}
