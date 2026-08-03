package eu.wohlben.qits.projects.gitmirror;

/**
 * How far a branch is ahead of and behind another, as {@code rev-list --left-right --count} counts
 * it — what a sync status or a branch listing reports.
 *
 * <p>{@code null} on both sides means git could not compare the two — an unresolvable ref, most
 * often a branch the mirror has not fetched yet. It is deliberately distinct from {@code (0, 0)}:
 * "in step" and "unknown" are different facts to show on screen.
 */
public record AheadBehind(Integer ahead, Integer behind) {

  public static final AheadBehind IN_STEP = new AheadBehind(0, 0);
  public static final AheadBehind UNKNOWN = new AheadBehind(null, null);
}
