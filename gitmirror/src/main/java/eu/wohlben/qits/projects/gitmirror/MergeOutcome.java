package eu.wohlben.qits.projects.gitmirror;

import java.util.List;

/**
 * What a merge — previewed in the object store, in {@code merge-tree --write-tree} — came to.
 *
 * <p>A conflict is an <b>answer</b>, not a failure, which is why it is a record and not an
 * exception: the caller turns it into a 400 carrying the file list and the resolution path, and the
 * file list is the only thing on screen a person can act on.
 *
 * @param clean true when the merge applied with no conflicts
 * @param conflictedPaths the conflicting files, empty when clean
 * @param output git's own words — for a clean merge, the written tree's sha on the first line,
 *     which {@link RepoMirror#commitTree} needs; for a conflict, kept for the failure messages that
 *     quote it
 */
public record MergeOutcome(boolean clean, List<String> conflictedPaths, String output) {

  public static MergeOutcome clean(String output) {
    return new MergeOutcome(true, List.of(), output);
  }

  public static MergeOutcome conflicted(List<String> paths, String output) {
    return new MergeOutcome(false, List.copyOf(paths), output);
  }

  /** The written tree's sha, out of a clean merge's first output line. Only valid when {@link #clean()}. */
  public String treeSha() {
    return output.lines().findFirst().orElseThrow().trim();
  }
}
