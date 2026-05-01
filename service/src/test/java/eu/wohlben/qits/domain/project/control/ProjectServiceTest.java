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
        var project = projectService.create("test-create", "Test Project", "A test project");

        assertEquals("test-create", project.id);
        assertEquals("Test Project", project.name);
        assertEquals("A test project", project.description);

        var found = projectService.get("test-create");
        assertEquals(project.id, found.id);
    }

    @Test
    public void testCreateDuplicateIdThrows() {
        projectService.create("test-dup", "First", null);

        assertThrows(BadRequestException.class, () ->
            projectService.create("test-dup", "Second", null)
        );
    }

    @Test
    public void testCreateMissingIdThrows() {
        assertThrows(BadRequestException.class, () ->
            projectService.create(null, "Name", null)
        );
    }

    @Test
    public void testCreateMissingNameThrows() {
        assertThrows(BadRequestException.class, () ->
            projectService.create("test-no-name", null, null)
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
        projectService.create("test-list-1", "One", null);
        projectService.create("test-list-2", "Two", null);

        var list = projectService.list();
        assertEquals(before + 2, list.size());
    }

    @Test
    public void testUpdate() {
        projectService.create("test-update", "Original", "Desc");

        var updated = projectService.update("test-update", "Updated", "New desc");

        assertEquals("Updated", updated.name);
        assertEquals("New desc", updated.description);
    }

    @Test
    public void testUpdatePartial() {
        projectService.create("test-update-partial", "Original", "Desc");

        var updated = projectService.update("test-update-partial", null, "New desc");

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
        projectService.create("test-delete", "ToDelete", null);

        assertNotNull(projectService.get("test-delete"));

        projectService.delete("test-delete");
        entityManager.flush();
        entityManager.clear();

        assertThrows(NotFoundException.class, () ->
            projectService.get("test-delete")
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
        projectService.create("test-del-with-repos", "Delete Me", null);

        var repo = new Repository();
        repo.id = "test-del-repo";
        repo.url = "https://example.com/repo.git";
        repo.archetype = RepositoryArchetype.SERVICE;
        repositoryRepository.persist(repo);

        projectService.associateRepository("test-del-with-repos", "test-del-repo");

        projectService.delete("test-del-with-repos");
        entityManager.flush();
        entityManager.clear();

        assertThrows(NotFoundException.class, () ->
            projectService.get("test-del-with-repos")
        );

        var dangling = repositoryRepository.findByIdOptional("test-del-repo").orElseThrow();
        assertNull(dangling.project);
    }

    @Test
    public void testGetRepositoriesEmpty() {
        projectService.create("test-repos-empty", "Empty", null);

        var repos = projectService.getRepositories("test-repos-empty");
        assertTrue(repos.isEmpty());
    }

    @Test
    @Transactional
    public void testAssociateAndDisassociateRepository() {
        projectService.create("test-assoc-proj", "Assoc Project", null);

        var repo = new Repository();
        repo.id = "test-assoc-repo";
        repo.url = "https://example.com/repo.git";
        repo.archetype = RepositoryArchetype.SERVICE;
        repositoryRepository.persist(repo);

        var associated = projectService.associateRepository("test-assoc-proj", "test-assoc-repo");
        assertNotNull(associated.project);
        assertEquals("test-assoc-proj", associated.project.id);

        var repos = projectService.getRepositories("test-assoc-proj");
        assertEquals(1, repos.size());
        assertEquals("test-assoc-repo", repos.get(0).id);

        var disassociated = projectService.disassociateRepository("test-assoc-proj", "test-assoc-repo");
        assertNull(disassociated.project);

        var reposAfter = projectService.getRepositories("test-assoc-proj");
        assertTrue(reposAfter.isEmpty());
    }

    @Test
    @Transactional
    public void testAssociateRepositoryAlreadyAssociatedThrows() {
        projectService.create("test-assoc-proj-a", "Assoc A", null);
        projectService.create("test-assoc-proj-b", "Assoc B", null);

        var repo = new Repository();
        repo.id = "test-assoc-repo-dup";
        repo.url = "https://example.com/repo.git";
        repo.archetype = RepositoryArchetype.SERVICE;
        repositoryRepository.persist(repo);

        projectService.associateRepository("test-assoc-proj-a", "test-assoc-repo-dup");

        assertThrows(BadRequestException.class, () ->
            projectService.associateRepository("test-assoc-proj-b", "test-assoc-repo-dup")
        );
    }

    @Test
    @Transactional
    public void testDisassociateRepositoryNotAssociatedThrows() {
        projectService.create("test-disassoc-proj", "Disassoc", null);

        var repo = new Repository();
        repo.id = "test-disassoc-repo";
        repo.url = "https://example.com/repo.git";
        repo.archetype = RepositoryArchetype.SERVICE;
        repositoryRepository.persist(repo);

        assertThrows(NotFoundException.class, () ->
            projectService.disassociateRepository("test-disassoc-proj", "test-disassoc-repo")
        );
    }

    @Test
    @Transactional
    public void testDisassociateRepositoryFromWrongProjectThrows() {
        projectService.create("test-disassoc-wrong-a", "Wrong A", null);
        projectService.create("test-disassoc-wrong-b", "Wrong B", null);

        var repo = new Repository();
        repo.id = "test-disassoc-wrong-repo";
        repo.url = "https://example.com/repo.git";
        repo.archetype = RepositoryArchetype.SERVICE;
        repositoryRepository.persist(repo);

        projectService.associateRepository("test-disassoc-wrong-a", "test-disassoc-wrong-repo");

        assertThrows(NotFoundException.class, () ->
            projectService.disassociateRepository("test-disassoc-wrong-b", "test-disassoc-wrong-repo")
        );
    }
}
