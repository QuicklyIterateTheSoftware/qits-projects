package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.*;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class RepositoryDiscoveryServiceTest {

  @Inject RepositoryDiscoveryService discoveryService;

  @Inject RepositoryRepository repositoryRepository;

  @Inject MetadataService metadataService;

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

  // SEAM (migration-plan.md §6, repository <-> workspace). Three tests stood here —
  // testDiscoverWithWorkspaces, testDiscoverAbandonsOrphanedWorkspaces and
  // testDiscoveryAbandonmentDeletesPromptDraftAndAttachments — and all three exercised the
  // container<->row reconcile that RepositoryDiscoveryService no longer does (see the seam note in
  // that class). They assert over Workspace/WorkspaceEvent/WorkspacePromptDraft/
  // WorkspacePromptAttachment, every one of them qits-workspaces' and in another database.
  //
  // NOTE FOR THE ORCHESTRATOR: they are UNOWNED as of this extraction, together with the code they
  // covered. They belong in qits-workspaces alongside a startup reconciler.
}
