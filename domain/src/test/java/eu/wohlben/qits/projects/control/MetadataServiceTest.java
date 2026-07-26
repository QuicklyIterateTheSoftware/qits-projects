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

  @Test
  public void testWriteAndReadWorkspaceMetadata() {
    WorkspaceMetadata wt = new WorkspaceMetadata();
    wt.workspaceId = "wt-01";
    wt.parent = "master";

    metadataService.writeWorkspaceMetadata("test-repo", wt);

    Optional<WorkspaceMetadata> read = metadataService.readWorkspaceMetadata("test-repo", "wt-01");
    assertTrue(read.isPresent());
    assertEquals("wt-01", read.get().workspaceId);
    assertEquals("master", read.get().parent);
  }

  @Test
  public void testReadAllWorkspaceMetadata() {
    WorkspaceMetadata wt1 = new WorkspaceMetadata();
    wt1.workspaceId = "wt-01";
    wt1.parent = null;

    WorkspaceMetadata wt2 = new WorkspaceMetadata();
    wt2.workspaceId = "wt-02";
    wt2.parent = "wt-01";

    metadataService.writeWorkspaceMetadata("test-repo", wt1);
    metadataService.writeWorkspaceMetadata("test-repo", wt2);

    var all = metadataService.readAllWorkspaceMetadata("test-repo");
    assertEquals(2, all.size());
  }

  @Test
  public void testDeleteWorkspaceMetadata() {
    WorkspaceMetadata wt = new WorkspaceMetadata();
    wt.workspaceId = "wt-01";
    wt.parent = null;

    metadataService.writeWorkspaceMetadata("test-repo", wt);
    assertTrue(metadataService.readWorkspaceMetadata("test-repo", "wt-01").isPresent());

    metadataService.deleteWorkspaceMetadata("test-repo", "wt-01");
    assertTrue(metadataService.readWorkspaceMetadata("test-repo", "wt-01").isEmpty());
  }
}
