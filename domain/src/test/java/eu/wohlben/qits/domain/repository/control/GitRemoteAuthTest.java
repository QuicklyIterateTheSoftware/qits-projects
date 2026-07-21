package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The credential seam of the remote-touching git verbs: the {@code -c} flag shape (ambient-helper
 * reset first, then the file-backed store), the {@code 0600} store file, and the auth-failure
 * classifier behind the {@code remote-auth} segment hint.
 */
@QuarkusTest
class GitRemoteAuthTest {

  @Inject GitRemoteAuth remoteAuth;

  @Test
  void credentialArgsAppendTheStoreHelperWithoutResettingTheAmbientList() {
    List<String> args = remoteAuth.credentialArgs();

    // Deliberately NO `credential.helper=` reset: appending (not substituting) keeps a
    // host-configured ambient helper working — the qits store is the fallback the sign-in fills.
    assertEquals(2, args.size());
    assertEquals("-c", args.get(0));
    assertTrue(args.get(1).startsWith("credential.helper=store --file="), args.get(1));
    // The configured test path, absolutized (see src/test/resources/application.properties).
    assertTrue(args.get(1).endsWith("target/test-data/git-credentials"), args.get(1));
  }

  @Test
  void theStoreFileIsCreatedOwnerOnly() throws Exception {
    Path path = remoteAuth.credentialsPath();

    assertTrue(Files.exists(path));
    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(path));
  }

  @Test
  void gitWithCredentialsSplicesTheFlagsBeforeTheVerb() {
    String[] argv = remoteAuth.gitWithCredentials("fetch", "--end-of-options", "url", "branch");

    assertEquals("git", argv[0]);
    assertEquals("-c", argv[1]);
    assertTrue(argv[2].startsWith("credential.helper=store --file="), argv[2]);
    assertEquals("fetch", argv[3]);
    assertEquals("--end-of-options", argv[4]);
    assertEquals("url", argv[5]);
    assertEquals("branch", argv[6]);
  }

  @Test
  void theClassifierMatchesTheKnownAuthSignaturesAnywhereInTheOutput() {
    // The no-TTY username prompt failure (GIT_TERMINAL_PROMPT=0 turns a hang into this).
    assertTrue(
        GitRemoteAuth.isAuthFailure(
            "fatal: could not read Username for 'https://github.com': No such device or address"));
    // The generic https auth rejection (github/gitlab PAT rejections).
    assertTrue(
        GitRemoteAuth.isAuthFailure(
            "remote: Invalid username or token.\n"
                + "fatal: Authentication failed for 'https://gitlab.example.com/group/repo.git/'"));
    // GitLab's basic-auth denial rides a remote: line.
    assertTrue(GitRemoteAuth.isAuthFailure("remote: HTTP Basic: Access denied"));
    // A plain 403.
    assertTrue(
        GitRemoteAuth.isAuthFailure(
            "error: The requested URL returned error: 403 while accessing"
                + " https://example.com/repo.git"));
    // GitHub's private-repo disguise: 404 "not found" for a repo the token can't see.
    assertTrue(
        GitRemoteAuth.isAuthFailure(
            "remote: Repository not found.\n"
                + "fatal: repository 'https://github.com/acme/private.git/' not found"));
  }

  @Test
  void theClassifierIgnoresOrdinaryPushAndPullFailures() {
    // A hook rejection is not an auth problem.
    assertFalse(
        GitRemoteAuth.isAuthFailure(
            "remote: rejected by policy\n"
                + " ! [remote rejected] master -> master (pre-receive hook declined)"));
    // Neither is a non-fast-forward rejection…
    assertFalse(
        GitRemoteAuth.isAuthFailure(
            " ! [rejected] master -> master (non-fast-forward)\n"
                + "error: failed to push some refs to 'https://example.com/repo.git'"));
    // …nor a diverged pull, an unreachable host, or nothing at all.
    assertFalse(
        GitRemoteAuth.isAuthFailure("Branch 'main' has diverged from the remote; manual merge"));
    assertFalse(
        GitRemoteAuth.isAuthFailure("fatal: unable to access 'https://example.com/': Could not"));
    assertFalse(GitRemoteAuth.isAuthFailure(null));
  }
}
