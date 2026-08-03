package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The test-side {@link GitHostRepositories}: {@code git init --bare -b <branch>} at the address
 * {@link GitHostAddress} names, standing in for qits-artifacts' {@code PUT}/{@code GET
 * /artifacts/git/<repoId>} (projects-volume-decoupling-plan.md §2.3, §5).
 *
 * <p>The only implementation of this <b>mandatory</b> port on the {@code domain} test classpath —
 * {@code HttpGitHostRepositories} lives in {@code service/src/main} and is not visible here — so no
 * {@code @DefaultBean}/{@code @Alternative} dance is needed for it to win.
 *
 * <p>Every bare it creates advertises push options ({@code receive.advertisePushOptions=true}),
 * which JGit does in production and a local {@code receive-pack} does not by default — without it
 * {@code git push --push-option} (the {@code -o qits.no-ci} publish path) would refuse the exact
 * argv that ships. The same fixture lesson as qits-workspaces' {@code TestOrigin} and this module's
 * own {@code TestBare}.
 *
 * <p>It also installs a {@code post-receive} hook recording the push options the <em>last</em>
 * accepted push carried, read back by {@link #lastPushOptions}: what a test asserts {@code
 * -o qits.no-ci} against, the same fact {@code CiPostReceiveNotifier} reads off {@code
 * ReceivePack.getPushOptions()} in production (§2.4).
 */
@ApplicationScoped
public class FakeGitHostRepositories implements GitHostRepositories {

  @Inject GitHostAddress gitHost;

  @Override
  public boolean ensure(String repoId, String defaultBranch) {
    Path bare = bareOf(repoId);
    if (Files.isDirectory(bare)) {
      return false;
    }
    try {
      Files.createDirectories(bare.getParent());
    } catch (IOException e) {
      throw new GitHostException("Could not create the fake host directory for " + repoId, e);
    }
    run(bare.getParent().toFile(), "git", "init", "--bare", "-q", "-b", defaultBranch, bare.toString());
    run(bare.toFile(), "git", "config", "receive.advertisePushOptions", "true");
    installPushOptionRecorder(bare);
    return true;
  }

  @Override
  public Optional<HostRepository> find(String repoId) {
    Path bare = bareOf(repoId);
    if (!Files.isDirectory(bare)) {
      return Optional.empty();
    }
    return Optional.of(new HostRepository(repoId, symbolicRef(bare)));
  }

  /**
   * The push options the last push {@link #ensure}'s bare accepted carried, in the order git sent
   * them — empty for a repository with no accepted push yet.
   */
  public List<String> lastPushOptions(String repoId) {
    Path log = bareOf(repoId).resolve("last-push-options.log");
    if (!Files.isRegularFile(log)) {
      return List.of();
    }
    try {
      return Files.readAllLines(log).stream().filter(line -> !line.isBlank()).toList();
    } catch (IOException e) {
      throw new GitHostException("Could not read " + log, e);
    }
  }

  private Path bareOf(String repoId) {
    return Path.of(gitHost.fetchUrl(repoId));
  }

  private String symbolicRef(Path bare) {
    return run(bare.toFile(), "git", "symbolic-ref", "--short", "HEAD").trim();
  }

  /**
   * A hook script rather than Java: the fact under test is what <em>git itself</em> received on the
   * wire ({@code GIT_PUSH_OPTION_COUNT}/{@code GIT_PUSH_OPTION_<n>}), which only a real
   * {@code receive-pack} invocation populates.
   */
  private void installPushOptionRecorder(Path bare) {
    try {
      Path hooksDir = bare.resolve("hooks");
      Files.createDirectories(hooksDir);
      Path hook = hooksDir.resolve("post-receive");
      String script =
          "#!/bin/sh\n"
              + "log=\"$GIT_DIR/last-push-options.log\"\n"
              + ": > \"$log\"\n"
              + "i=0\n"
              + "while [ \"$i\" -lt \"${GIT_PUSH_OPTION_COUNT:-0}\" ]; do\n"
              + "  eval \"opt=\\$GIT_PUSH_OPTION_$i\"\n"
              + "  echo \"$opt\" >> \"$log\"\n"
              + "  i=$((i + 1))\n"
              + "done\n";
      Files.writeString(hook, script);
      if (!hook.toFile().setExecutable(true)) {
        throw new GitHostException("Could not make the push-option recorder executable: " + hook);
      }
    } catch (IOException e) {
      throw new GitHostException("Could not install the push-option recorder in " + bare, e);
    }
  }

  private String run(File cwd, String... argv) {
    ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true);
    try {
      Process process = pb.start();
      String output = new String(process.getInputStream().readAllBytes());
      if (process.waitFor() != 0) {
        throw new GitHostException("git " + String.join(" ", argv) + " failed:\n" + output);
      }
      return output;
    } catch (IOException e) {
      throw new GitHostException("Could not run git " + String.join(" ", argv), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitHostException("Interrupted running git " + String.join(" ", argv), e);
    }
  }
}
