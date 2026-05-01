package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(MetadataServiceTest.TestProfile.class)
public class MetadataServiceTest {

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
    MetadataService metadataService;

    @Test
    public void testWriteAndReadRepositoryMetadata() {
        Repository repo = new Repository();
        repo.id = "test-repo";
        repo.url = "https://example.com/repo.git";
        repo.archetype = RepositoryArchetype.SERVICE;

        metadataService.writeRepositoryMetadata(repo);

        Optional<RepositoryMetadata> read = metadataService.readRepositoryMetadata("test-repo");
        assertTrue(read.isPresent());
        assertEquals("test-repo", read.get().id);
        assertEquals("https://example.com/repo.git", read.get().url);
        assertEquals(RepositoryArchetype.SERVICE, read.get().archetype);
    }

    @Test
    public void testReadMissingRepositoryMetadata() {
        Optional<RepositoryMetadata> read = metadataService.readRepositoryMetadata("nonexistent");
        assertTrue(read.isEmpty());
    }

    @Test
    public void testWriteAndReadWorktreeMetadata() {
        WorktreeMetadata wt = new WorktreeMetadata();
        wt.worktreeId = "wt-01";
        wt.parent = "master";

        metadataService.writeWorktreeMetadata("test-repo", wt);

        Optional<WorktreeMetadata> read = metadataService.readWorktreeMetadata("test-repo", "wt-01");
        assertTrue(read.isPresent());
        assertEquals("wt-01", read.get().worktreeId);
        assertEquals("master", read.get().parent);
    }

    @Test
    public void testReadAllWorktreeMetadata() {
        WorktreeMetadata wt1 = new WorktreeMetadata();
        wt1.worktreeId = "wt-01";
        wt1.parent = null;

        WorktreeMetadata wt2 = new WorktreeMetadata();
        wt2.worktreeId = "wt-02";
        wt2.parent = "wt-01";

        metadataService.writeWorktreeMetadata("test-repo", wt1);
        metadataService.writeWorktreeMetadata("test-repo", wt2);

        var all = metadataService.readAllWorktreeMetadata("test-repo");
        assertEquals(2, all.size());
    }

    @Test
    public void testDeleteWorktreeMetadata() {
        WorktreeMetadata wt = new WorktreeMetadata();
        wt.worktreeId = "wt-01";
        wt.parent = null;

        metadataService.writeWorktreeMetadata("test-repo", wt);
        assertTrue(metadataService.readWorktreeMetadata("test-repo", "wt-01").isPresent());

        metadataService.deleteWorktreeMetadata("test-repo", "wt-01");
        assertTrue(metadataService.readWorktreeMetadata("test-repo", "wt-01").isEmpty());
    }
}
