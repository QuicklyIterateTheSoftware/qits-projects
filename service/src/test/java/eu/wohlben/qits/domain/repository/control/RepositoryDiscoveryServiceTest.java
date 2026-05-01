package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorktreeRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

    @Test
    public void testDiscoverNewRepository() throws Exception {
        String repoId = "discovered-repo";
        Path repoDir = Path.of(metadataService.getDataDir(), repoId);
        Files.createDirectories(repoDir.resolve("origin"));

        RepositoryMetadata meta = new RepositoryMetadata();
        meta.id = repoId;
        meta.url = "https://example.com/discovered.git";
        meta.archetype = RepositoryArchetype.SERVICE;

        Repository repo = new Repository();
        repo.id = repoId;
        repo.url = meta.url;
        repo.archetype = meta.archetype;
        metadataService.writeRepositoryMetadata(repo);

        discoveryService.discover();

        Repository found = repositoryRepository.findByIdOptional(repoId).orElse(null);
        assertNotNull(found);
        assertEquals(meta.url, found.url);
        assertEquals(meta.archetype, found.archetype);
    }

    @Test
    public void testDiscoverWithWorktrees() throws Exception {
        String repoId = "repo-with-worktrees";
        Path repoDir = Path.of(metadataService.getDataDir(), repoId);
        Files.createDirectories(repoDir.resolve("origin"));

        Repository repo = new Repository();
        repo.id = repoId;
        repo.url = "https://example.com/wt.git";
        repo.archetype = RepositoryArchetype.SERVICE;
        metadataService.writeRepositoryMetadata(repo);

        WorktreeMetadata wt = new WorktreeMetadata();
        wt.worktreeId = "wt-01";
        wt.parent = null;
        metadataService.writeWorktreeMetadata(repoId, wt);

        discoveryService.discover();

        assertTrue(worktreeRepository.findByRepositoryAndWorktreeId(repoId, "wt-01").isPresent());
    }

    @Test
    public void testDiscoverRemovesOrphanedWorktrees() throws Exception {
        String repoId = "repo-orphan";
        Path repoDir = Path.of(metadataService.getDataDir(), repoId);
        Files.createDirectories(repoDir.resolve("origin"));

        Repository repo = new Repository();
        repo.id = repoId;
        repo.url = "https://example.com/orphan.git";
        repo.archetype = RepositoryArchetype.SERVICE;
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
