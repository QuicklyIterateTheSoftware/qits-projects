package eu.wohlben.qits.projects.entity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What kind of part of its project a repository is.
 *
 * <p>The four <b>placeable</b> archetypes ({@link #SERVICE}, {@link #LIBRARY}, {@link
 * #INTEGRATION}, {@link #APPLICATION}) are exactly the directories of the project template skeleton
 * every {@link #PROJECT} wrapper is seeded with — directory <em>is</em> archetype, in both
 * directions: a directory extracted out of {@code libs/} into a sibling repository becomes a {@code
 * LIBRARY}, and a {@code LIBRARY} is mounted back under {@code libs/}. The mapping lives here
 * rather than being derived from the name, because it doesn't derive mechanically ({@code libs} !=
 * {@code LIBRARY}, {@code apps} != {@code APPLICATION}).
 *
 * <p>{@link #SERVICE_TEMPLATE} and {@link #FORK} are deliberately unplaceable: neither is a
 * component of <em>this</em> application (one is scaffolding a component is generated from, the
 * other an external downstream fork), so neither has a home in the wrapper's tree nor is a valid
 * extraction target. {@link #PROJECT} is unplaceable for the opposite reason — it <em>is</em> the
 * tree.
 *
 * <p>Adding a value here also requires a Flyway migration: {@code Repository.archetype} carries a
 * DB check constraint over the value set (V44 rebuilt V1's inline one as the named {@code
 * CK_repository_archetype}).
 */
public enum RepositoryArchetype {
  /** The project's wrapper repository — the root superproject. At most one per project. */
  PROJECT(null),
  /** A deployable component. */
  SERVICE("services"),
  /** Shared technical code consumed by the components. */
  LIBRARY("libs"),
  /** An adapter/client toward another system. */
  INTEGRATION("integrations"),
  /** An end-user-facing app (a SPA, a CLI). */
  APPLICATION("apps"),
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
