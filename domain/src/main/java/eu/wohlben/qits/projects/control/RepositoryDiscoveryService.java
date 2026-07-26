package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RepositoryDiscoveryService {

  private static final Logger LOG = Logger.getLogger(RepositoryDiscoveryService.class);

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @Inject MetadataService metadataService;

  @Inject RepositoryRepository repositoryRepository;

  @Transactional
  void onStart(@Observes StartupEvent event) {
    LOG.info("Starting repository discovery...");
    discover();
    LOG.info("Repository discovery complete.");
  }

  @Transactional
  public void discover() {
    Path dataPath = Path.of(dataDir);
    if (!Files.exists(dataPath)) {
      return;
    }

    try (var stream = Files.list(dataPath)) {
      List<Path> subdirs = stream.filter(Files::isDirectory).toList();
      for (Path repoDir : subdirs) {
        String repoId = repoDir.getFileName().toString();
        Path originPath = repoDir.resolve("origin");
        if (!Files.exists(originPath)) {
          continue;
        }

        Optional<RepositoryMetadata> metadataOpt = metadataService.readRepositoryMetadata(repoId);
        Repository repo = repositoryRepository.findByIdOptional(repoId).orElse(null);
        if (repo == null) {
          LOG.warnf(
              "Discovered repository %s on disk but it has no project association; skipping",
              repoId);
          continue;
        }

        if (metadataOpt.isPresent()) {
          RepositoryMetadata metadata = metadataOpt.get();
          repo.url = metadata.url;
          repo.archetype = metadata.archetype;
        }
        // SEAM (migration-plan.md §6, repository <-> workspace). What stood here was the
        // workspace half of discovery: reconciling ACTIVE `workspace` rows against the live
        // containers, marking STOPPED/ABANDONED, reaping dangling per-workspace volumes and hard
        // deleting orphaned prompt drafts + attachment BLOBs. Every table and every collaborator it
        // touched — Workspace, WorkspaceEvent, WorkspaceRepository, WorkspaceEventRepository,
        // WorkspacePromptDraftRepository, WorkspacePromptAttachmentRepository, WorkspaceService,
        // ContainerRuntime — is WS_REPO, i.e. qits-workspaces', and lives in another database (§7).
        // It cannot be a port either: it is not a question this context asks, it is work the
        // workspaces context does. So it is cut, not translated.
        //
        // NOTE FOR THE ORCHESTRATOR: as of this extraction that reconcile is UNOWNED —
        // qits-workspaces ships no startup reconciler. Same shape as qits-ci's dropped
        // branchDeletionRecordsNoRun. It belongs in qits-workspaces.
      }
    } catch (Exception e) {
      throw new RuntimeException("Repository discovery failed", e);
    }
  }
}
