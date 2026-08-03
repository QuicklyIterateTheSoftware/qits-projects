package eu.wohlben.qits.projects.gitmirror;

/**
 * The test-side {@link GitCredentials}: wraps nothing beyond the {@code git} the real
 * implementation also prepends. The throwaway bares {@link TestBare} builds need no auth, so this
 * is what a deployment with no credentials configured would do too.
 */
final class NoCredentials implements GitCredentials {

  @Override
  public String[] wrap(String... gitArgs) {
    String[] argv = new String[gitArgs.length + 1];
    argv[0] = "git";
    System.arraycopy(gitArgs, 0, argv, 1, gitArgs.length);
    return argv;
  }
}
