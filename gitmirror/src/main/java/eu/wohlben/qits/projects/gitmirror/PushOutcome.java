package eu.wohlben.qits.projects.gitmirror;

/**
 * What receive-pack said.
 *
 * <p>A rejected push is an answer and not a failure — the caller reads it for which refusal it was
 * (a hook, a lost fast-forward race) and each one is a different sentence to a person. A push that
 * never happened at all — no transport, a timeout — is a {@link GitMirrorException} instead, because
 * there is nothing in the output to classify.
 *
 * @param accepted true when git exited zero
 * @param output the combined output, which is the whole of what a refusal can be classified from
 */
public record PushOutcome(boolean accepted, String output) {

  /**
   * The git host's own words out of a {@code [remote rejected]} line — e.g. the protection hook
   * refusing a default-branch delete. {@code git push} renders the reason in trailing parentheses;
   * the whole line is the fallback, because a refusal a human cannot read is worse than a verbose
   * one. Null when no such line is present.
   */
  public String remoteRefusal() {
    if (output == null) {
      return null;
    }
    for (String line : output.split("\n")) {
      if (!line.contains("[remote rejected]") && !line.contains("[remote failure]")) {
        continue;
      }
      int open = line.indexOf('(');
      int close = line.lastIndexOf(')');
      if (open >= 0 && close > open) {
        return line.substring(open + 1, close).trim();
      }
      return line.trim();
    }
    return null;
  }

  /** Whether git reported the update as not a fast-forward — a race lost to another writer. */
  public boolean saysNotFastForward() {
    String lower = output == null ? "" : output.toLowerCase();
    return lower.contains("non-fast-forward")
        || lower.contains("fetch first")
        || lower.contains("[rejected]");
  }
}
