package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryName;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The name-alias table: resolves {@code (projectId, name) → Repository} for the git host and
 * registers new aliases on import/creation. A repository may have many aliases; each {@code
 * (project, name)} maps to exactly one repository (the DB unique constraint).
 */
@ApplicationScoped
public class RepositoryNameRepository implements PanacheRepositoryBase<RepositoryName, String> {

  private static final Logger LOG = Logger.getLogger(RepositoryNameRepository.class);

  /**
   * The addressable name of a repository url: its basename with any {@code .git} suffix stripped —
   * {@code https://h/o/foo.git} → {@code foo}, {@code /abs/foo.git} → {@code foo}, {@code
   * git@host:o/foo.git} → {@code foo}. This is exactly the segment git requests when resolving a
   * relative submodule url {@code ../foo.git} against a sibling under {@code /git/<projectId>/}.
   */
  public static String basename(String url) {
    String u = url == null ? "" : url.trim();
    while (u.length() > 1 && u.endsWith("/")) {
      u = u.substring(0, u.length() - 1);
    }
    int slash = u.lastIndexOf('/');
    String last = slash >= 0 ? u.substring(slash + 1) : u;
    int colon = last.lastIndexOf(':'); // scp-style user@host:path
    if (colon >= 0) {
      last = last.substring(colon + 1);
    }
    if (last.endsWith(".git")) {
      last = last.substring(0, last.length() - 4);
    }
    return last;
  }

  /** The repository addressed by {@code name} within {@code projectId}, if any. */
  public Optional<Repository> findRepositoryByProjectAndName(String projectId, String name) {
    return find("project.id = ?1 and name = ?2", projectId, name)
        .firstResultOptional()
        .map(alias -> alias.repository);
  }

  /** The alias row for {@code (projectId, name)}, if any. */
  public Optional<RepositoryName> findByProjectAndName(String projectId, String name) {
    return find("project.id = ?1 and name = ?2", projectId, name).firstResultOptional();
  }

  /** Any name that resolves to {@code repository} — the name its container clones itself under. */
  public Optional<String> nameFor(Repository repository) {
    return find("repository.id", repository.id).firstResultOptional().map(alias -> alias.name);
  }

  /**
   * <b>Every</b> name that resolves to {@code repository}. A repository can be addressed by more
   * than one, so matching a wrapper's {@code .gitmodules} entry against a single one would miss a
   * member declared under an alias it also owns.
   */
  public List<String> namesFor(Repository repository) {
    return find("repository.id", repository.id).stream().map(alias -> alias.name).toList();
  }

  /**
   * Ensure {@code (project, name) → repository} exists. Idempotent: a row already mapping to {@code
   * repository} is a no-op; a row mapping to a <em>different</em> repository is a genuine basename
   * collision (two distinct urls whose basenames coincide within one project) — first writer wins,
   * and this logs without overwriting, since native {@code ../name.git} resolution is inherently
   * ambiguous there. Used to register each referencing submodule name as an alias of its child.
   */
  public void ensureAlias(Project project, String name, Repository repository) {
    Optional<RepositoryName> existing = findByProjectAndName(project.id, name);
    if (existing.isPresent()) {
      if (!existing.get().repository.id.equals(repository.id)) {
        LOG.warnf(
            "Repository name collision in project %s: '%s' already maps to repository %s, keeping it"
                + " (not remapping to %s). Native submodule resolution of ../%s.git is ambiguous"
                + " here.",
            project.id, name, existing.get().repository.id, repository.id, name);
      }
      return;
    }
    RepositoryName alias = new RepositoryName();
    alias.project = project;
    alias.repository = repository;
    alias.name = name;
    persist(alias);
  }

  /**
   * Guarantee {@code repository} owns at least one addressable name and return it — the name its
   * workspace container clones itself under. Prefers the url basename (so relative submodule
   * resolution from this repo-as-superproject stays natural); if that basename is already owned by
   * a <em>different</em> repository in the project, falls back to a deterministic {@code
   * <basename>-<id-prefix>} that is effectively unique. Called at repository creation, so every
   * repository has a self-name before it is ever provisioned.
   */
  public String registerSelfName(Repository repository) {
    String base = basename(repository.url);
    // A url-less repository (a greenfield wrapper) basenames to "", which is not addressable. Such
    // a
    // repository should come through registerSelfName(repository, name); this only keeps a blank
    // alias from ever reaching the unique (project, name) index.
    return registerSelfName(repository, base.isBlank() ? repository.id : base);
  }

  /**
   * {@link #registerSelfName(Repository)} with an explicit preferred name — the wrapper path, where
   * there is no url to derive a basename from and the name is derived from the project slug
   * instead.
   *
   * <p>Note the disambiguation fallback below is <em>wrong</em> for a wrapper: {@code
   * qits-qits-a1b2c3d4} would quietly break the local-alias-equals-remote-basename invariant that
   * relative submodule urls depend on. Wrapper callers therefore assert the name is free first and
   * fail loudly rather than relying on this.
   */
  public String registerSelfName(Repository repository, String preferredName) {
    String base = preferredName;
    Optional<Repository> owner = findRepositoryByProjectAndName(repository.project.id, base);
    if (owner.isEmpty()) {
      ensureAlias(repository.project, base, repository);
      return base;
    }
    if (owner.get().id.equals(repository.id)) {
      return base;
    }
    String disambiguated =
        base + "-" + repository.id.substring(0, Math.min(8, repository.id.length()));
    ensureAlias(repository.project, disambiguated, repository);
    return disambiguated;
  }
}
