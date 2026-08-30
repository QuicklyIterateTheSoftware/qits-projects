package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import java.util.List;

/**
 * One {@code .gitmodules} path, read under either wrapper layout.
 *
 * <p>The wrapper is the project's configuration and its paths are what that configuration says, so
 * there is exactly one reading of a path and this is it — the reconcile derives a row's facts from
 * it, and the create flow places a new entry with it.
 *
 * <p>Two layouts, and both are supported at once because the flip is gradual:
 *
 * <ul>
 *   <li><b>archetype layout</b> — {@code <directory>/<name>}, where the directory is the archetype
 *       ({@link RepositoryArchetype#fromDirectory}). The component is null: this layout has no such
 *       fact to state.
 *   <li><b>component layout</b> — {@code components/<component>/<name>}, where the first segment is
 *       the literal {@code components} and the second names the technical component. The directory
 *       declares no archetype at all, which is why the reconcile preserves the row's own.
 * </ul>
 *
 * <p>Anything else — one segment, {@code components/<x>} with nothing under it, a deeper tree — is
 * read as the archetype layout and comes back with a directory no archetype claims, which the
 * reconcile already skips with a warning. Guessing what {@code vendor/} or {@code components/a/b/c}
 * means would be inventing taxonomy.
 *
 * <p>Pure and static, like {@link WrapperGitmodules}: no CDI, no git, no IO.
 *
 * @param path the whole path, trimmed
 * @param directory the mount directory under the archetype layout; null under the component layout
 * @param component the component under the component layout; null under the archetype layout
 * @param name the last segment — the repository's addressable name, what {@code ../<name>.git}
 *     resolves to
 */
public record WrapperPath(String path, String directory, String component, String name) {

  /** The first segment that marks a path as the component layout's. */
  public static final String COMPONENTS_DIRECTORY = "components";

  /**
   * {@code path} read under whichever layout it is in, or null when it is not a usable path at all
   * (blank, or a single segment with no directory to mount under).
   */
  public static WrapperPath parse(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    String trimmed = path.trim();
    int lastSlash = trimmed.lastIndexOf('/');
    if (lastSlash <= 0 || lastSlash == trimmed.length() - 1) {
      return null;
    }
    String[] segments = trimmed.split("/");
    if (segments.length == 3
        && COMPONENTS_DIRECTORY.equals(segments[0])
        && !segments[1].isBlank()
        && !segments[2].isBlank()) {
      return new WrapperPath(trimmed, null, segments[1], segments[2]);
    }
    return new WrapperPath(
        trimmed, trimmed.substring(0, lastSlash), null, trimmed.substring(lastSlash + 1));
  }

  /** Whether this entry is mounted the component way. */
  public boolean isComponentLayout() {
    return component != null;
  }

  /**
   * The archetype this entry's <b>directory</b> declares — null under the component layout, where a
   * directory declares none, and null for a directory no archetype claims.
   */
  public RepositoryArchetype directoryArchetype() {
    return directory == null ? null : RepositoryArchetype.fromDirectory(directory);
  }

  /** The mount directory a component entry takes: {@code components/<component>}. */
  public static String componentDirectory(String component) {
    return COMPONENTS_DIRECTORY + "/" + component;
  }

  /**
   * Whether a manifest has flipped to the component layout — <b>any</b> entry mounted under {@code
   * components/}, not all of them, because a wrapper is flipped one entry at a time and the first
   * one moved is what says which layout the next create should place into.
   */
  public static boolean usesComponentLayout(List<WrapperGitmodules.Entry> entries) {
    return entries.stream()
        .map(entry -> parse(entry.path()))
        .anyMatch(parsed -> parsed != null && parsed.isComponentLayout());
  }
}
