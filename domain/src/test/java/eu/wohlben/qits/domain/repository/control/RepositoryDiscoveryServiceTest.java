package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.*;

import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.project.persistence.ProjectRepository;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.entity.WorkspaceStatus;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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

  @Inject RepositoryDiscoveryService discoveryService;

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject MetadataService metadataService;

  @Inject ContainerRuntime containers;

  @Inject ProjectRepository projectRepository;

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
  public void testDiscoverWithWorkspaces() throws Exception {
    String repoId = "repo-with-workspaces";
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

    // Workspace reconciliation is now keyed to live containers (their qits.* labels), not metadata
    // files: register a workspace container and discovery upserts its row.
    containers.run(repoId, "wt-01", "wt-01", null);

    discoveryService.discover();

    assertTrue(
        workspaceRepository.findActiveByRepositoryAndWorkspaceId(repoId, "wt-01").isPresent());
  }

  @Test
  @Transactional
  public void testDiscoverAbandonsOrphanedWorkspaces() throws Exception {
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

    containers.run(repoId, "orphan-wt", "orphan-wt", null);

    discoveryService.discover();
    assertTrue(
        workspaceRepository.findActiveByRepositoryAndWorkspaceId(repoId, "orphan-wt").isPresent());

    // The container is removed out-of-band; discovery then soft-deletes the now-orphaned row.
    containers.rm(containers.containerName("orphan-wt", repoId));

    discoveryService.discover();
    // Soft-delete: the workspace is no longer ACTIVE, but its row survives as history (marked
    // ABANDONED) rather than being removed.
    assertTrue(
        workspaceRepository.findActiveByRepositoryAndWorkspaceId(repoId, "orphan-wt").isEmpty());
    assertTrue(
        workspaceRepository.findByRepositoryId(repoId).stream()
            .anyMatch(
                w -> "orphan-wt".equals(w.workspaceId) && w.status == WorkspaceStatus.ABANDONED),
        "orphaned workspace should be kept as ABANDONED history");
  }
}
