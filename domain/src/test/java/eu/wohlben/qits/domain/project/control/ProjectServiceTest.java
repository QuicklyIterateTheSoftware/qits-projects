package eu.wohlben.qits.domain.project.control;

import static org.junit.jupiter.api.Assertions.*;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.project.persistence.ProjectRepository;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ProjectServiceTest {

  @Inject ProjectService projectService;

  @Inject ProjectRepository projectRepository;

  @Inject RepositoryRepository repositoryRepository;

  @Inject EntityManager entityManager;

  @Test
  public void testCreateAndGet() {
    var project = projectService.create("Test Project", "A test project");

    assertNotNull(project.id);
    assertEquals("Test Project", project.name);
    assertEquals("A test project", project.description);

    var found = projectService.get(project.id);
    assertEquals(project.id, found.id);
  }

  @Test
  public void testCreateMissingNameThrows() {
    assertThrows(BadRequestException.class, () -> projectService.create(null, null));
  }

  @Test
  public void testGetNotFoundThrows() {
    assertThrows(NotFoundException.class, () -> projectService.get("non-existent"));
  }

  @Test
  public void testList() {
    long before = projectRepository.count();
    projectService.create("One", null);
    projectService.create("Two", null);

    var list = projectService.list();
    assertEquals(before + 2, list.size());
  }

  @Test
  public void testUpdate() {
    var project = projectService.create("Original", "Desc");

    var updated = projectService.update(project.id, "Updated", "New desc");

    assertEquals("Updated", updated.name);
    assertEquals("New desc", updated.description);
  }

  @Test
  public void testUpdatePartial() {
    var project = projectService.create("Original", "Desc");

    var updated = projectService.update(project.id, null, "New desc");

    assertEquals("Original", updated.name);
    assertEquals("New desc", updated.description);
  }

  @Test
  public void testUpdateNotFoundThrows() {
    assertThrows(
        NotFoundException.class, () -> projectService.update("non-existent", "Name", null));
  }

  /**
   * Each step runs in its own transaction, the way every real caller reaches these methods (a
   * request per verb). A single test-wide transaction would instead keep the rows {@code create}
   * wrote — the wrapper, its workspace and that workspace's event — managed while their parent is
   * removed, which Hibernate rejects at flush; the absence assertion also needs a fresh context to
   * be meaningful, since an L1-cached read would satisfy it either way.
   */
  @Test
  public void testDelete() {
    var project = projectService.create("ToDelete", null);

    assertNotNull(projectService.get(project.id));

    projectService.delete(project.id);

    assertThrows(
        NotFoundException.class,
        () -> QuarkusTransaction.requiringNew().call(() -> projectService.get(project.id)));
  }

  @Test
  public void testDeleteNotFoundThrows() {
    assertThrows(NotFoundException.class, () -> projectService.delete("non-existent"));
  }

  /** Deleting a project takes its repositories with it — the wrapper included. */
  @Test
  public void testDeleteProjectWithRepositories() {
    var project = projectService.create("Delete Me", null);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var repo = new Repository();
              repo.id = "test-del-repo";
              repo.url = "https://example.com/repo.git";
              repo.archetype = RepositoryArchetype.SERVICE;
              repo.project = projectRepository.findById(project.id);
              repositoryRepository.persist(repo);
            });

    String wrapperId = projectService.findWrapper(project.id).orElseThrow().id;

    projectService.delete(project.id);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertThrows(NotFoundException.class, () -> projectService.get(project.id));
              assertTrue(repositoryRepository.findByIdOptional("test-del-repo").isEmpty());
              assertTrue(
                  repositoryRepository.findByIdOptional(wrapperId).isEmpty(),
                  "the wrapper must go with its project");
            });
  }

  /**
   * Project creation always ends with exactly one repository — the wrapper — so a project with no
   * repositories of its own is not empty, it holds its wrapper and nothing else.
   */
  @Test
  public void testGetRepositoriesHoldsOnlyTheWrapper() {
    var project = projectService.create("Empty", null);

    var repos = projectService.getRepositories(project.id);
    assertEquals(1, repos.size());
    assertEquals(RepositoryArchetype.PROJECT, repos.get(0).archetype);
  }
}
