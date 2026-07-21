package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The credential seam every <em>remote-touching</em> git verb shares (clone {@code --mirror}, the
 * pull walk's fetch, the sync-status probes, push): a {@code git credential-store} file under the
 * persistent data volume, <em>appended</em> to git's helper list per-invocation via {@code -c}
 * flags. The store starts empty and is filled by the interactive sign-in terminal (an interactive
 * {@code git push} in a host-side PTY); git then self-heals it — {@code approve} on success, {@code
 * reject} (erase) on a 401 — so one sign-in covers every repository on that host and a rotated
 * token cleanly re-triggers the sign-in flow. Local verbs (rev-parse, merge, update-ref, …) never
 * see these flags.
 *
 * <p>The store is <em>added</em> to, not substituted for, any host-configured credential helper: a
 * deployment that already authenticates private remotes through an ambient helper
 * (git-credential-manager, a gcloud/CodeCommit helper, a global store) keeps working — its helper
 * answers first and the qits store is the fallback that the sign-in terminal fills. (An earlier
 * {@code credential.helper=} reset that cleared the ambient list broke exactly those deployments:
 * clone has no sign-in flow, so a cleared list left no auth path at all.) {@code
 * GIT_TERMINAL_PROMPT=0} on the non-interactive spawns (see {@code GitExecutor}) keeps a prompting
 * ambient helper from hanging.
 *
 * <p>Helper-based auth deliberately keeps tokens out of argvs, URLs, and error output — nothing to
 * redact in streamed segments or logs (unlike embedding {@code user:token@} in the repository URL,
 * which would leak through every {@code GitExecutor} error message).
 */
@ApplicationScoped
public class GitRemoteAuth {

  /** Owner read/write only — the store holds plaintext credentials. */
  private static final Set<PosixFilePermission> CREDENTIALS_FILE_PERMISSIONS =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  /**
   * The known auth-failure signatures of an HTTPS remote, matched anywhere in git's merged output
   * (remote-side stderr arrives {@code remote:}-prefixed): the no-TTY username prompt failure, the
   * generic and GitLab basic-auth rejections, a plain 403, and GitHub's private-repo disguise (it
   * answers 404 "not found" for a private repository — which can false-positive on a genuinely
   * typo'd URL, acceptable: the sign-in terminal then shows git's real answer either way).
   */
  private static final List<Pattern> AUTH_FAILURE_SIGNATURES =
      List.of(
          Pattern.compile(Pattern.quote("could not read Username")),
          Pattern.compile(Pattern.quote("Authentication failed for")),
          Pattern.compile(Pattern.quote("HTTP Basic: Access denied")),
          Pattern.compile(Pattern.quote("The requested URL returned error: 403")),
          Pattern.compile("repository '[^']*' not found"));

  @ConfigProperty(
      name = "qits.repositories.credentials-file",
      defaultValue = "data/git-credentials")
  String credentialsFile;

  /**
   * The prepared, absolute store path — computed once (see {@link #prepare}); {@code null} until.
   */
  private volatile Path preparedPath;

  /**
   * The absolute path of the credential-store file, created {@code 0600} (with parent directories)
   * on first use so the interactive sign-in never widens a git-created file's permissions. Prepared
   * exactly once and memoized — {@link #credentialArgs} runs on every remote git verb, so this must
   * not re-hit the filesystem each time.
   */
  public Path credentialsPath() {
    Path path = preparedPath;
    return path != null ? path : prepare();
  }

  private synchronized Path prepare() {
    if (preparedPath != null) {
      return preparedPath;
    }
    Path path = Path.of(credentialsFile).toAbsolutePath();
    try {
      Files.createDirectories(path.getParent());
      try {
        Files.createFile(path);
      } catch (FileAlreadyExistsException alreadyThere) {
        // A prior run or a concurrent first-use caller created it — not an error (the
        // check-then-act
        // race that Files.exists()+createFile would otherwise surface as a spurious first-boot
        // 500).
      }
      // POSIX-only: on a filesystem without a POSIX view (e.g. a native-Windows checkout running
      // the
      // domain test suite) setPosixFilePermissions throws UnsupportedOperationException; skip it
      // there rather than fail every remote verb.
      if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(path, CREDENTIALS_FILE_PERMISSIONS);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to prepare git credentials file at " + path, e);
    }
    preparedPath = path;
    return path;
  }

  /**
   * The per-invocation {@code -c} flags: the file-backed store helper, <em>appended</em> to git's
   * ambient helper list (no reset — see the class note) so a host-configured helper still
   * authenticates and the qits store is the fallback the sign-in terminal fills.
   */
  public List<String> credentialArgs() {
    return List.of("-c", "credential.helper=store --file=" + credentialsPath());
  }

  /**
   * A full git argv with the credential flags spliced in before the verb: {@code git -c … <args>}.
   */
  public String[] gitWithCredentials(String... args) {
    List<String> credentialArgs = credentialArgs();
    String[] argv = new String[1 + credentialArgs.size() + args.length];
    argv[0] = "git";
    int i = 1;
    for (String c : credentialArgs) {
      argv[i++] = c;
    }
    for (String a : args) {
      argv[i++] = a;
    }
    return argv;
  }

  /**
   * Whether a failed remote verb's output matches a known auth signature — the classifier behind
   * the {@code remote-auth} hint on a settled segment.
   */
  public static boolean isAuthFailure(String output) {
    if (output == null) {
      return false;
    }
    return AUTH_FAILURE_SIGNATURES.stream().anyMatch(p -> p.matcher(output).find());
  }
}
