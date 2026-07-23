package eu.wohlben.qits.domain.repository.persistence;

import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryName;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
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
