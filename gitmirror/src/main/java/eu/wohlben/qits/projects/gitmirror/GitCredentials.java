package eu.wohlben.qits.projects.gitmirror;

/**
 * The credential seam for the one remote this module does not control: a repository's own backup
 * remote (an external forge), touched by {@link RepoMirror#cloneFrom} and {@link
 * RepoMirror#fetchIntoFetchHead}. The platform's own git host needs none — it is tokenless by
 * design, which is why {@link GitRemotes} carries no such seam.
 *
 * <p>Declared here so the module can name what it needs without depending on how the credential
 * store is implemented; {@code GitRemoteAuth} in {@code domain} implements it, and {@code
 * gitWithCredentials(String...)} already has this exact signature — nothing to adapt.
 */
public interface GitCredentials {

  /** A full git argv with any credential flags spliced in before the verb: {@code git -c … …}. */
  String[] wrap(String... gitArgs);
}
