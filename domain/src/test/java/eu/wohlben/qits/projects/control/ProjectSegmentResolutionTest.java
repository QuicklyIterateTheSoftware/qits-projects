package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The <b>project half</b> of {@code RepositoryService#findByProjectAndName}: which project the first
 * segment of {@code /git/<project>/<repoName>} names.
 *
 * <p>The segment is the project's id <b>or</b> its slug, and the slug is the public spelling — a
 * person is given {@code /git/qits/qits-ci} to clone, never a UUID. The id is matched first and
 * always works, so every machine path that already holds one is untouched.
 *
 * <p>Rows are persisted directly rather than created through {@code ProjectService}: the collision
 * case needs one project whose <em>id</em> is another project's <em>slug</em>, and a created
 * project's id is a minted UUID nobody chooses. Nothing here needs a git host, a wrapper or a
 * clone — the resolution reads two tables.
 */
@QuarkusTest
public class ProjectSegmentResolutionTest {

  @Inject RepositoryService repositoryService;

  @Inject ProjectRepository projectRepository;

  @Inject RepositoryRepository repositoryRepository;

  @Inject RepositoryNameRepository repositoryNameRepository;

  /**
   * A project row and one repository addressable under {@code name} within it.
   *
   * <p>An explicit transaction rather than {@code @Transactional}: this is called from a test
   * method on {@code this}, and a self-invocation reaches no interceptor at all.
   */
  private Repository seed(String projectId, String slug, String name) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = slug;
              project.slug = slug;
              projectRepository.persist(project);

              Repository repository = new Repository();
              repository.id = UUID.randomUUID().toString();
              repository.archetype = RepositoryArchetype.SERVICE;
              repository.project = project;
              repositoryRepository.persist(repository);

              repositoryNameRepository.ensureAlias(project, name, repository);
              return repository;
            });
  }

  /** Both coordinates address the same repository, and neither is a special case of the other. */
  @Test
  public void aRepositoryResolvesUnderTheProjectSlugAndUnderItsId() {
    String projectId = UUID.randomUUID().toString();
    Repository repository = seed(projectId, "segment-public", "checkout");

    assertEquals(
        repository.id,
        repositoryService.findByProjectAndName(projectId, "checkout").orElseThrow().id,
        "the id is the machine spelling and still resolves");
    assertEquals(
        repository.id,
        repositoryService.findByProjectAndName("segment-public", "checkout").orElseThrow().id,
        "the slug is the public spelling of the same clone url");
  }

  /**
   * A segment that is one project's id <em>and</em> another's slug is the id's. Slugs are unique
   * among live projects (V6), so the ambiguity can only ever be this one — and resolving it towards
   * the id is what makes every machine-held id unconditionally safe to send. The consequence is
   * stated rather than hidden: the project holding that slug is not reachable under it.
   */
  @Test
  public void anIdSegmentWinsOverAnotherProjectsSlug() {
    Repository byId = seed("segment-clash", "segment-clash-owner", "checkout");
    Repository bySlug = seed(UUID.randomUUID().toString(), "segment-clash", "checkout");

    assertEquals(
        byId.id,
        repositoryService.findByProjectAndName("segment-clash", "checkout").orElseThrow().id,
        "the id arm decided it");
    assertEquals(
        bySlug.id,
        repositoryService.findByProjectAndName(bySlug.project.id, "checkout").orElseThrow().id,
        "and the shadowed project is still reachable by its own id");
  }

  /** A segment naming no project resolves nothing — the git host's 404, not an empty project. */
  @Test
  public void aSegmentNamingNoProjectResolvesNothing() {
    seed(UUID.randomUUID().toString(), "segment-absent", "checkout");

    assertTrue(repositoryService.findByProjectAndName("no-such-project", "checkout").isEmpty());
    assertTrue(repositoryService.findByProjectAndName("segment-absent", "no-such-repo").isEmpty());
    assertTrue(repositoryService.findByProjectAndName("", "checkout").isEmpty());
    assertTrue(repositoryService.findByProjectAndName(null, "checkout").isEmpty());
  }
}
