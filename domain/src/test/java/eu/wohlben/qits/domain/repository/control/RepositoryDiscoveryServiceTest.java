package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.*;

import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.project.persistence.ProjectRepository;
import eu.wohlben.qits.domain.repository.entity.PromptAttachmentSource;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptAttachment;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptDraft;
import eu.wohlben.qits.domain.repository.entity.WorkspaceStatus;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptAttachmentRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptDraftRepository;
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

  @Inject WorkspacePromptDraftRepository promptDraftRepository;

  @Inject WorkspacePromptAttachmentRepository promptAttachmentRepository;

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

  @Test
  @Transactional
  public void testDiscoveryAbandonmentDeletesPromptDraftAndAttachments() throws Exception {
    String repoId = "repo-abandon-draft";
    Path repoDir = Path.of(metadataService.getDataDir(), repoId);
    Files.createDirectories(repoDir.resolve("origin"));

    Project project = createProject();

    Repository repo = new Repository();
    repo.id = repoId;
    repo.url = "https://example.com/abandon-draft.git";
    repo.archetype = RepositoryArchetype.SERVICE;
    repo.project = project;
    repositoryRepository.persist(repo);

    metadataService.writeRepositoryMetadata(repo);

    containers.run(repoId, "draft-wt", "draft-wt", null);
    discoveryService.discover();
    Workspace workspace =
        workspaceRepository.findActiveByRepositoryAndWorkspaceId(repoId, "draft-wt").orElseThrow();

    // Seed the pre-launch composition state (a draft blob + an image attachment) that the
    // other termination paths hard-delete on abandon.
    WorkspacePromptDraft draft = new WorkspacePromptDraft();
    draft.workspaceId = workspace.id;
    draft.content = "{\"v\":1}";
    promptDraftRepository.persist(draft);

    WorkspacePromptAttachment attachment = new WorkspacePromptAttachment();
    attachment.id = UUID.randomUUID().toString();
    attachment.workspaceId = workspace.id;
    attachment.mimeType = "image/png";
    attachment.label = "Sketch 1";
    attachment.source = PromptAttachmentSource.SKETCH;
    attachment.bytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
    promptAttachmentRepository.persist(attachment);

    // The container vanishes and the workspace has no branch to recreate from, so the next
    // discovery reconciliation takes the ABANDONED path — which must leave no orphaned draft/BLOB.
    containers.rm(containers.containerName("draft-wt", repoId));
    discoveryService.discover();

    // discover()'s cleanup is a bulk DELETE, which bypasses the L1 persistence cache — flush +
    // clear
    // so the assertions below read the DB, not the still-managed seeded entities (this whole test
    // shares one transaction; in production discover() runs in its own).
    promptDraftRepository.getEntityManager().flush();
    promptDraftRepository.getEntityManager().clear();

    assertTrue(
        workspaceRepository.findActiveByRepositoryAndWorkspaceId(repoId, "draft-wt").isEmpty());
    assertTrue(
        promptDraftRepository.findByWorkspaceId(workspace.id).isEmpty(),
        "draft row must be gone after discovery abandonment");
    assertTrue(
        promptAttachmentRepository.listByWorkspaceId(workspace.id).isEmpty(),
        "attachment rows must be gone after discovery abandonment");
  }
}
