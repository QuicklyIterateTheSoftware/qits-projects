package eu.wohlben.qits.projects.entity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What kind of part of its project a repository is.
 *
 * <p>The six <b>placeable</b> archetypes ({@link #SERVICE}, {@link #DAEMON}, {@link #LIBRARY},
 * {@link #FRONTEND}, {@link #CLI}, {@link #IMAGE}) are exactly the directories of the project
 * template skeleton every {@link #PROJECT} wrapper is seeded with — directory <em>is</em> archetype,
 * in both directions: a directory extracted out of {@code libs/} into a sibling repository becomes a
 * {@code LIBRARY}, and a {@code LIBRARY} is mounted back under {@code libs/}. The mapping lives here
 * rather than being derived from the name, because it doesn't derive mechanically ({@code libs} !=
 * {@code LIBRARY}, {@code frontends} != {@code FRONTEND}).
 *
 * <p>Directory is now <b>authoritative</b>: the wrapper's {@code .gitmodules} is the project's
 * configuration, so the directory an entry sits under decides the row's archetype — see {@link
 * #fromDirectory}, which is the reconcile's derivation.
 *
 * <p>{@link #SERVICE_TEMPLATE} and {@link #FORK} are deliberately unplaceable: neither is a
 * component of <em>this</em> application (one is scaffolding a component is generated from, the
 * other an external downstream fork), so neither has a home in the wrapper's tree nor is a valid
 * extraction target. {@link #PROJECT} is unplaceable for the opposite reason — it <em>is</em> the
 * tree.
 *
 * <p>{@link #INTEGRATION} and {@link #APPLICATION} are <b>deprecated aliases</b> kept only so
 * Hibernate can read rows written before the taxonomy was reworked. They are unplaceable, nothing
 * writes them any more ({@link #normalize()} runs on every write path), and they are deleted in
 * release B together with the row updates that retire the last of them.
 *
 * <p>Adding a value here also requires a Flyway migration: {@code Repository.archetype} carries a
 * DB check constraint over the value set (V44 rebuilt V1's inline one as the named {@code
 * CK_repository_archetype}; V3 widened it for this rework).
 */
public enum RepositoryArchetype {
  /** The project's wrapper repository — the root superproject. At most one per project. */
  PROJECT(null),
  /** A deployable component. */
  SERVICE("services"),
  /** A long-running background agent — deployed rather than served. */
  DAEMON("daemons"),
  /** Shared technical code consumed by the components. */
  LIBRARY("libs"),
  /** Anything served to a user at a URL. */
  FRONTEND("frontends"),
  /** A command-line entry point into the application. */
  CLI("cli"),
  /** A build definition consumed through its published OCI image. */
  IMAGE("images"),
  /** Scaffolding a component is generated <em>from</em>, not part of the application. */
  SERVICE_TEMPLATE(null),
  /** A downstream fork — an external repository, never inline. */
  FORK(null),

  /**
   * @deprecated merged into {@link #LIBRARY}. Readable, never written — see {@link #normalize()}.
   */
  @Deprecated
  INTEGRATION(null),

  /**
   * @deprecated renamed to {@link #FRONTEND}. Readable, never written — see {@link #normalize()}.
   */
  @Deprecated
  APPLICATION(null);

  private final String directory;

  RepositoryArchetype(String directory) {
    this.directory = directory;
  }

  /**
   * The wrapper skeleton directory a repository of this archetype is mounted under, or {@code null}
   * when the archetype is unplaceable.
   */
  public String directory() {
    return directory;
  }

  /** Whether repositories of this archetype have a home in the wrapper's tree. */
  public boolean isPlaceable() {
    return directory != null;
  }

  /**
   * The archetype a wrapper entry under {@code directory} declares, or {@code null} when no
   * archetype claims that directory — the reconcile's derivation, and the reason an unknown
   * directory is a skip with a warning rather than a guess.
   */
  public static RepositoryArchetype fromDirectory(String directory) {
    if (directory == null || directory.isBlank()) {
      return null;
    }
    String trimmed = directory.trim();
    for (RepositoryArchetype archetype : values()) {
      if (trimmed.equals(archetype.directory)) {
        return archetype;
      }
    }
    return null;
  }

  /**
   * This archetype in the current taxonomy: {@code INTEGRATION} → {@link #LIBRARY}, {@code
   * APPLICATION} → {@link #FRONTEND}, everything else unchanged (null included).
   *
   * <p>Called on <b>every write path</b>, so release A never writes a deprecated value while still
   * reading the rows release B's migration retires.
   */
  @SuppressWarnings("deprecation")
  public RepositoryArchetype normalize() {
    return switch (this) {
      case INTEGRATION -> LIBRARY;
      case APPLICATION -> FRONTEND;
      default -> this;
    };
  }

  /** {@link #normalize()} for a possibly-null value. */
  public static RepositoryArchetype normalize(RepositoryArchetype archetype) {
    return archetype == null ? null : archetype.normalize();
  }

  /**
   * Every skeleton directory, in declaration order — the set the project template must contain
   * exactly, which {@code RepositoryArchetypeTemplateSyncTest} asserts in both directions.
   */
  public static Set<String> skeletonDirectories() {
    return Arrays.stream(values())
        .map(RepositoryArchetype::directory)
        .filter(d -> d != null)
        .collect(LinkedHashSet::new, Set::add, Set::addAll);
  }
}
