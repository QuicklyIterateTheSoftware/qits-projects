package eu.wohlben.qits.epics.control;

import java.util.Collection;
import java.util.Locale;

/**
 * Git-safe slugs for epics, features and tasks — the path segments of the branch names the platform
 * mints: {@code epic/<epic>}, {@code feature/<epic>/<feature>}, {@code
 * task/<epic>/<feature>/<task>}.
 */
public final class Slugs {

  private static final int MAX_LENGTH = 40;

  private Slugs() {}

  /**
   * Derives a git-safe slug from a title: lowercase, every run of non-alphanumerics becomes a dash,
   * leading/trailing dashes stripped, capped at 40 characters.
   *
   * <p>A deliberate copy of {@code ProjectService.slugify} in the {@code domain} module: epics must
   * not depend on {@code domain}, so the rule is duplicated rather than shared. Change one and
   * change the other.
   *
   * <p><b>Total by construction</b> — a title with nothing alphanumeric in it ({@code "***"}, a
   * pure-unicode title) slugifies to the empty string, so it falls back to {@code fallbackPrefix}
   * plus the id's first 8 characters, which are UUID hex and therefore always valid.
   * {@code V2__slugs.sql}'s backfill mirrors this in SQL.
   */
  public static String slugify(String title, String entityId, String fallbackPrefix) {
    String slug =
        (title == null ? "" : title)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
    if (slug.length() > MAX_LENGTH) {
      // The cut can land on a dash, which a trailing dash is not allowed to be.
      slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
    }
    if (slug.isEmpty()) {
      return fallbackPrefix
          + entityId.substring(0, Math.min(8, entityId.length())).toLowerCase(Locale.ROOT);
    }
    return slug;
  }

  /**
   * Returns {@code base} when no sibling holds it, else the first free {@code -2}, {@code -3}, …,
   * trimming {@code base} so the whole stays within 40 characters.
   *
   * <p>This diverges from {@code Project.slug}, which is deliberately <em>not</em> unique: these
   * slugs are branch path segments, so two siblings sharing one would name the same branch.
   *
   * <p>The check is a read before a write with no lock, so two concurrent creates of the same title
   * in the same scope can both pass it and the second then fails the unique constraint as a 500.
   * That is accepted: planning writes are hand-driven, and a retry succeeds.
   */
  public static String unique(String base, Collection<String> taken) {
    if (!taken.contains(base)) {
      return base;
    }
    for (int n = 2; ; n++) {
      String suffix = "-" + n;
      String head =
          base.length() + suffix.length() <= MAX_LENGTH
              ? base
              : base.substring(0, MAX_LENGTH - suffix.length()).replaceAll("-+$", "");
      String candidate = head + suffix;
      if (!taken.contains(candidate)) {
        return candidate;
      }
    }
  }
}
