package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.project.persistence.ProjectRepository;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorktreeRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(RepositoryDiscoveryServiceTest.TestProfile.class)
public class RepositoryDiscoveryServiceTest {

    public static class TestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Path tempDir = Files.createTempDirectory("qits-test-repos");
                return Map.of("qits.repositories.data-dir", tempDir.toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Inject
    RepositoryDiscoveryService discoveryService;

    @Inject
    RepositoryRepository repositoryRepository;

    @Inject
    WorktreeRepository worktreeRepository;

    @Inject
    MetadataService metadataService;

    @Inject
    ProjectRepository projectRepository;

    private Project createProject() {
        Project project = new Project();
        project.id = UUID.randomUUID().toString();
        project.name = "Discovery Project";
        projectRepository.persist(project);
        return project;
    }

    @Test
    @Transactional
    public void testDiscoverUpdatesExistingRepository() throws Exception {
        String repoId = "discovered-repo";
        Path repoDir = Path.of(metadataService.getDataDir(), repoId);
        Files.createDirectories(repoDir.resolve("origin"));

        Project project = createProject();

        Repository repo = new Repository();
        repo.id = repoId;
        repo.url = "https://example.com/old.git";
        repo.archetype = RepositoryArchetype.FORK;
        repo.project = project;
        repositoryRepository.persist(repo);

        Repository metaRepo = new Repository();
        metaRepo.id = repoId;
        metaRepo.url = "https://example.com/discovered.git";
        metaRepo.archetype = RepositoryArchetype.SERVICE;
        metadataService.writeRepositoryMetadata(metaRepo);

        discoveryService.discover();

        Repository found = repositoryRepository.findByIdOptional(repoId).orElse(null);
        assertNotNull(found);
        assertEquals("https://example.com/discovered.git", found.url);
        assertEquals(RepositoryArchetype.SERVICE, found.archetype);
    }

    @Test
    @Transactional
    public void testDiscoverWithWorktrees() throws Exception {
        String repoId = "repo-with-worktrees";
        Path repoDir = Path.of(metadataService.getDataDir(), repoId);
        Files.createDirectories(repoDir.resolve("origin"));

        Project project = createProject();

        Repository repo = new Repository();
        repo.id = repoId;
        repo.url = "https://example.com/wt.git";
        repo.archetype = RepositoryArchetype.SERVICE;
        repo.project = project;
        repositoryRepository.persist(repo);

        metadataService.writeRepositoryMetadata(repo);

        WorktreeMetadata wt = new WorktreeMetadata();
        wt.worktreeId = "wt-01";
        wt.parent = null;
        metadataService.writeWorktreeMetadata(repoId, wt);

        discoveryService.discover();

        assertTrue(worktreeRepository.findByRepositoryAndWorktreeId(repoId, "wt-01").isPresent());
    }

    @Test
    @Transactional
    public void testDiscoverRemovesOrphanedWorktrees() throws Exception {
        String repoId = "repo-orphan";
        Path repoDir = Path.of(metadataService.getDataDir(), repoId);
        Files.createDirectories(repoDir.resolve("origin"));

        Project project = createProject();

        Repository repo = new Repository();
        repo.id = repoId;
        repo.url = "https://example.com/orphan.git";
        repo.archetype = RepositoryArchetype.SERVICE;
        repo.project = project;
        repositoryRepository.persist(repo);

        metadataService.writeRepositoryMetadata(repo);

        WorktreeMetadata wt = new WorktreeMetadata();
        wt.worktreeId = "orphan-wt";
        wt.parent = null;
        metadataService.writeWorktreeMetadata(repoId, wt);

        discoveryService.discover();
        assertTrue(worktreeRepository.findByRepositoryAndWorktreeId(repoId, "orphan-wt").isPresent());

        metadataService.deleteWorktreeMetadata(repoId, "orphan-wt");

        discoveryService.discover();
        assertTrue(worktreeRepository.findByRepositoryAndWorktreeId(repoId, "orphan-wt").isEmpty());
    }
}
