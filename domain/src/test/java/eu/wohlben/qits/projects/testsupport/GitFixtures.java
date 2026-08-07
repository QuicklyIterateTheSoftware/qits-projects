package eu.wohlben.qits.projects.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a git fixture to an absolute path on disk, building the derived ones on first use.
 *
 * <p><b>Why this exists.</b> In the monorepo the {@code testing-repo*} fixtures are committed as
 * <em>git submodules</em> (working trees), and a {@code derive-fixture-bares} antrun step
 * (scripts/derive-fixture-bares.sh, bound to {@code process-test-resources}) turned each one into a
 * bare repo on the test classpath. migration-plan.md §8 step 4 drops that antrun step, and
 * §5 lists the four fixture gitlinks as "already separate repos, re-attach as submodules". Neither
 * option survives the extraction gate: re-attaching submodules makes {@code mvn verify} from a
 * plain {@code git clone} fail (nothing is checked out, and the derive step exits non-zero), and
 * that gate — green from a clone of this repo alone, no monorepo, no docker, no credentials — is
 * the whole point. So the fixtures are <b>built in-test</b>, like qits-ci's and qits-artifacts'.
 *
 * <p>Only the DERIVED bares are synthesized. The {@code submodule-*.git} fixtures are committed
 * bare repos in {@code src/test/resources/fixtures} and are used exactly as they are; this class
 * returns their classpath path unchanged. The derived ones are written <em>into the same
 * directory</em>, which is where the antrun step put them, so a superproject's relative submodule
 * url still folds to a sibling bare.
 *
 * <p>{@code testing-repo.git} reproduces the fixture repository commit for commit — two branches
 * off a shared root, {@code master} three commits deep so a rewind-and-fast-forward has somewhere
 * to go, {@code feature} diverging with its own file, and both touching {@code hello.txt} so a
 * forced conflict is reachable. The commit shas differ from the original (author, dates); nothing
 * asserts on them.
 */
public final class GitFixtures {

  private GitFixtures() {}

  private static volatile Path fixturesDir;

  /**
   * The absolute path of the fixture {@code name} (e.g. {@code testing-repo.git}), building it
   * first if it is one of the derived ones.
   */
  public static synchronized String path(String name) {
    Path dir = fixturesDir();
    if (!Files.exists(dir.resolve(name))) {
      buildDerived(dir);
    }
    Path fixture = dir.resolve(name);
    if (!Files.exists(fixture)) {
      throw new IllegalArgumentException("No such git fixture: " + name);
    }
    return fixture.toString();
  }

  /**
   * The fixtures directory on the test classpath, anchored on a committed fixture rather than on
   * {@code getResource("/fixtures")} — a directory URL a test-time write would not refresh.
   */
  private static Path fixturesDir() {
    Path dir = fixturesDir;
    if (dir == null) {
      var url = GitFixtures.class.getResource("/fixtures/submodule-super.git/HEAD");
      if (url == null) {
        throw new IllegalStateException("The committed git fixtures are not on the test classpath");
      }
      try {
        dir = Path.of(url.toURI()).getParent().getParent();
      } catch (Exception e) {
        throw new IllegalStateException("Cannot locate the fixtures directory", e);
      }
      fixturesDir = dir;
    }
    return dir;
  }

  private static void buildDerived(Path dir) {
    Path work = dir.resolve(".build-testing-repo");
    deleteRecursively(work);
    try {
      Files.createDirectories(work);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    // A three-commit master and a feature branch forked from its middle commit, so:
    //   - master~1 exists (the rewind-then-fast-forward case),
    //   - neither branch is an ancestor of the other (the divergence cases),
    //   - both carry hello.txt (a real content conflict is reachable by rewriting it on both).
    git(work, "init", "-q", "-b", "master", ".");
    git(work, "config", "user.email", "fixtures@qits.local");
    git(work, "config", "user.name", "qits fixtures");
    write(work.resolve("README.md"), "# Test Repository\n");
    git(work, "add", "-A");
    git(work, "commit", "-q", "-m", "Initial commit");
    write(work.resolve("hello.txt"), "hello world\n");
    git(work, "add", "-A");
    git(work, "commit", "-q", "-m", "Add hello.txt");

    git(work, "checkout", "-q", "-b", "feature");
    write(work.resolve("feature.txt"), "feature work\n");
    git(work, "add", "-A");
    git(work, "commit", "-q", "-m", "Add feature.txt");

    git(work, "checkout", "-q", "master");
    write(
        work.resolve("README.md"),
        "# testing-repo — Test Fixture\n\nA tiny repository with known commits and branches,"
            + " built by GitFixtures for the qits-projects suite.\n");
    git(work, "add", "-A");
    git(work, "commit", "-q", "-m", "docs: add README explaining fixture purpose and structure");

    // The derived bares the monorepo's derive-fixture-bares.sh produced for this context.
    // demo-demo.git, empty-empty.git and qits-qits.git carry names no other fixture can stand in
    // for: a project wrapper may only be adopted from a url whose basename is <slug>-<slug>.
    pushBare(work, dir.resolve("testing-repo.git"), "master");
    // A NON-empty upstream for the adopt path (project slug `demo`): real history adoption must
    // leave completely untouched.
    pushBare(work, dir.resolve("demo-demo.git"), "master");
    // An EMPTY upstream (project slug `empty`): no refs at all, HEAD dangling at an unborn `main` —
    // a forge repository created and never pushed to. Adopting it must yield the project template
    // skeleton.
    emptyBare(dir.resolve("empty-empty.git"), "main");
    // A WRAPPER upstream (project slug `qits`) carrying a real .gitmodules: the manifest a whole
    // project is restored from. Its entries are relative, so they fold against this very directory
    // and land on the sibling bares beside it — the same resolution a forge and the platform's
    // name-addressed git route both perform.
    wrapperBare(dir.resolve("qits-qits.git"));

    deleteRecursively(work);
  }

  /**
   * A bare wrapper repository on {@code main} whose single commit is a {@code .gitmodules}: one
   * entry per placeable directory that resolves to a sibling fixture, plus one under a directory no
   * archetype claims (the reconcile must skip that one and say why).
   */
  private static void wrapperBare(Path bare) {
    deleteRecursively(bare);
    git(bare.getParent(), "init", "-q", "--bare", bare.getFileName().toString());
    Path work = bare.getParent().resolve(".build-wrapper");
    deleteRecursively(work);
    try {
      Files.createDirectories(work);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    git(work, "init", "-q", "-b", "main", ".");
    git(work, "config", "user.email", "fixtures@qits.local");
    git(work, "config", "user.name", "qits fixtures");
    write(
        work.resolve(".gitmodules"),
        """
        [submodule "submodule-shared"]
        \tpath = libs/submodule-shared
        \turl = ../submodule-shared.git
        \tbranch = main
        [submodule "submodule-grandchild"]
        \tpath = services/submodule-grandchild
        \turl = ../submodule-grandchild.git
        \tbranch = main
        [submodule "vendored"]
        \tpath = vendor/vendored
        \turl = ../submodule-simple-super.git
        \tbranch = main
        """);
    write(work.resolve("README.md"), "# qits-qits — wrapper fixture\n");
    git(work, "add", "-A");
    git(work, "commit", "-q", "-m", "Declare the project's components");
    git(work, "push", "-q", bare.toString(), "main:refs/heads/main");
    git(bare, "symbolic-ref", "HEAD", "refs/heads/main");
    deleteRecursively(work);
  }

  /** Mirror {@code work}'s branches into a fresh bare at {@code bare}, with HEAD on {@code head}. */
  private static void pushBare(Path work, Path bare, String head) {
    deleteRecursively(bare);
    git(bare.getParent(), "init", "-q", "--bare", bare.getFileName().toString());
    git(work, "push", "-q", bare.toString(), "master:refs/heads/master", "feature:refs/heads/feature");
    git(bare, "symbolic-ref", "HEAD", "refs/heads/" + head);
  }

  private static void emptyBare(Path bare, String head) {
    deleteRecursively(bare);
    git(bare.getParent(), "init", "-q", "--bare", bare.getFileName().toString());
    git(bare, "symbolic-ref", "HEAD", "refs/heads/" + head);
  }

  private static void write(Path file, String content) {
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void git(Path cwd, String... args) {
    String[] argv = new String[args.length + 1];
    argv[0] = "git";
    System.arraycopy(args, 0, argv, 1, args.length);
    try {
      // A stable, minimal environment: the developer's ~/.gitconfig must not decide whether a
      // fixture builds (templates, hooks, gpg signing, a default branch name, includeIf blocks).
      Map<String, String> env = new LinkedHashMap<>();
      env.put("GIT_CONFIG_GLOBAL", "/dev/null");
      env.put("GIT_CONFIG_SYSTEM", "/dev/null");
      env.put("GIT_AUTHOR_NAME", "qits fixtures");
      env.put("GIT_AUTHOR_EMAIL", "fixtures@qits.local");
      env.put("GIT_COMMITTER_NAME", "qits fixtures");
      env.put("GIT_COMMITTER_EMAIL", "fixtures@qits.local");
      ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
      pb.environment().putAll(env);
      Process process = pb.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IllegalStateException(
            "git " + String.join(" ", args) + " failed (" + exit + "):\n" + output);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                  // best effort — a leftover entry only costs a rebuild next run
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
