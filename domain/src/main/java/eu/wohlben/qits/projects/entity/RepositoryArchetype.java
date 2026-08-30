package eu.wohlben.qits.projects.entity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
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
 * <p>Directory is authoritative <b>under the archetype layout</b>: the wrapper's {@code .gitmodules}
 * is the project's configuration, so the directory an entry sits under decides the row's archetype —
 * see {@link #fromDirectory}, which is the reconcile's derivation there.
 *
 * <p><b>Under the component layout it is the name that says the kind</b>, because the first path
 * segment is the literal {@code components} and the second is the component. {@link
 * #fromRepositoryName} is that derivation, over the role suffix of the campaign's name grammar
 * ({@code <component>[-<modifier>]-<role>[-<tech>]}). It answers null for a name carrying no role
 * suffix, which is the state every repository is in until the renames land — and null is what the
 * reconcile stores rather than a guess, since nothing in this service can correct a wrong archetype
 * afterwards.
 *
 * <p>{@link #SERVICE_TEMPLATE} and {@link #FORK} are deliberately unplaceable: neither is a
 * component of <em>this</em> application (one is scaffolding a component is generated from, the
 * other an external downstream fork), so neither has a home in the wrapper's tree nor is a valid
 * extraction target. {@link #PROJECT} is unplaceable for the opposite reason — it <em>is</em> the
 * tree.
 *
 * <p>These nine are the whole set. {@code INTEGRATION} and {@code APPLICATION} were carried through
 * release A as deprecated, unplaceable aliases so Hibernate could read rows written before the
 * rework; V4 retired the last of those rows and they are gone.
 *
 * <p>Adding a value here also requires a Flyway migration: {@code Repository.archetype} carries a
 * DB check constraint over the value set (V44 rebuilt V1's inline one as the named {@code
 * CK_repository_archetype}; V3 widened it for this rework and V4 tightened it to these nine).
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
  FORK(null);

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
   * The role suffixes of the name grammar, mapped to the archetype each one declares. Insertion
   * order is the matching order; no suffix is a suffix of another, so the order is documentation
   * rather than precedence.
   */
  private static final Map<String, RepositoryArchetype> ROLE_SUFFIXES =
      Map.of(
          "-service", SERVICE,
          "-daemon", DAEMON,
          "-frontend", FRONTEND,
          "-oci", IMAGE,
          "-cli", CLI,
          "-javalib", LIBRARY,
          "-jslib", LIBRARY);

  /**
   * The archetype a repository <em>name</em> declares through its role suffix, or null when it
   * declares none.
   *
   * <p>This is the component layout's derivation: {@code qits-ci-service} is a {@link #SERVICE},
   * {@code qits-eventstream-javalib} a {@link #LIBRARY}, {@code qits-workspace-oci} an {@link
   * #IMAGE}. A tier modifier sits <em>before</em> the role ({@code
   * qits-deployments-platform-service}), so the suffix still decides.
   *
   * <p><b>Null is the answer for every name the renames have not reached</b> — {@code qits-ci},
   * {@code qits-spa-ci} — and it is a real answer, not a failure: the reconcile only ever asks this
   * for a row it is creating, and it stores the null rather than inventing a kind.
   */
  public static RepositoryArchetype fromRepositoryName(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String trimmed = name.trim().toLowerCase(java.util.Locale.ROOT);
    for (Map.Entry<String, RepositoryArchetype> role : ROLE_SUFFIXES.entrySet()) {
      if (trimmed.endsWith(role.getKey()) && trimmed.length() > role.getKey().length()) {
        return role.getValue();
      }
    }
    return null;
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
