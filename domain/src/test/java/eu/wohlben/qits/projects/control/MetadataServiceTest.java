package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.*;

import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class MetadataServiceTest {

  @Inject MetadataService metadataService;

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

  // SEAM (migration-plan.md §6, repository <-> workspace). Three tests over the workspace
  // metadata sidecars (write/read, readAll, delete) went with the methods they covered — see the
  // seam note in MetadataService. WorkspaceMetadata is qits-workspaces'. The repository sidecar
  // tests above are unchanged.
}
