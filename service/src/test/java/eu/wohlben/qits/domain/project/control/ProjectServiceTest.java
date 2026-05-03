package eu.wohlben.qits.domain.project.control;

import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.project.persistence.ProjectRepository;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class ProjectServiceTest {

    @Inject
    ProjectService projectService;

    @Inject
    ProjectRepository projectRepository;

    @Inject
    RepositoryRepository repositoryRepository;

    @Inject
    EntityManager entityManager;

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
        assertThrows(BadRequestException.class, () ->
            projectService.create(null, null)
        );
    }

    @Test
    public void testGetNotFoundThrows() {
        assertThrows(NotFoundException.class, () ->
            projectService.get("non-existent")
        );
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
        assertThrows(NotFoundException.class, () ->
            projectService.update("non-existent", "Name", null)
        );
    }

    @Test
    @Transactional
    public void testDelete() {
        var project = projectService.create("ToDelete", null);

        assertNotNull(projectService.get(project.id));

        projectService.delete(project.id);
        entityManager.flush();
        entityManager.clear();

        assertThrows(NotFoundException.class, () ->
            projectService.get(project.id)
        );
    }

    @Test
    public void testDeleteNotFoundThrows() {
        assertThrows(NotFoundException.class, () ->
            projectService.delete("non-existent")
        );
    }

    @Test
    @Transactional
    public void testDeleteProjectWithRepositories() {
        var project = projectService.create("Delete Me", null);

        var repo = new Repository();
        repo.id = "test-del-repo";
        repo.url = "https://example.com/repo.git";
        repo.archetype = RepositoryArchetype.SERVICE;
        repo.project = project;
        repositoryRepository.persist(repo);
        entityManager.flush();

        projectService.delete(project.id);
        entityManager.flush();
        entityManager.clear();

        assertThrows(NotFoundException.class, () ->
            projectService.get(project.id)
        );

        assertTrue(repositoryRepository.findByIdOptional("test-del-repo").isEmpty());
    }

    @Test
    public void testGetRepositoriesEmpty() {
        var project = projectService.create("Empty", null);

        var repos = projectService.getRepositories(project.id);
        assertTrue(repos.isEmpty());
    }
}
