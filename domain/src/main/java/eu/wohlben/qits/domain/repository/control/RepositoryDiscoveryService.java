package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.entity.WorkspaceEvent;
import eu.wohlben.qits.domain.repository.entity.WorkspaceEventType;
import eu.wohlben.qits.domain.repository.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.domain.repository.entity.WorkspaceStatus;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceEventRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RepositoryDiscoveryService {

  private static final Logger LOG = Logger.getLogger(RepositoryDiscoveryService.class);

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @Inject MetadataService metadataService;

  @Inject ContainerRuntime containers;

  @Inject WorkspaceService workspaceService;

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceEventRepository workspaceEventRepository;

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

        // Workspaces are now containers, not on-disk checkouts, so reconcile the DB against the
        // live containers (keyed by their qits.* labels) rather than the metadata sidecar files.
        List<ContainerRuntime.ContainerInfo> containerList =
            containers.listWorkspaceContainers(repoId);
        Set<String> containerWorkspaceIds = new HashSet<>();
        for (ContainerRuntime.ContainerInfo info : containerList) {
          containerWorkspaceIds.add(info.workspaceId());
          // Upsert against the ACTIVE row only, so a resolved workspace of the same id is never
          // revived back to ACTIVE; a container with no active row means a fresh workspace whose
          // row
          // was lost (rebuild it from the labels).
          Workspace workspace =
              workspaceRepository
                  .findActiveByRepositoryAndWorkspaceId(repoId, info.workspaceId())
                  .orElseGet(
                      () -> {
                        Workspace w = new Workspace();
                        w.workspaceId = info.workspaceId();
                        w.repository = repo;
                        w.status = WorkspaceStatus.ACTIVE;
                        workspaceRepository.persist(w);
                        workspaceEventRepository.persist(
                            WorkspaceEvent.builder()
                                .workspace(w)
                                .type(WorkspaceEventType.CREATED)
                                .branch(info.branch())
                                .parent(info.parent())
                                .at(Instant.now())
                                .build());
                        return w;
                      });
          workspace.parent = info.parent();
          workspace.branch = info.branch();
          workspace.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
          workspaceRepository.persist(workspace);
        }

        // Reconcile ACTIVE rows whose container is absent. The durable branch — not the container —
        // is the source of truth, so a missing container is not death: if the branch still exists
        // we
        // only lost a recreatable cache (mark STOPPED; lazy ensureContainer re-provisions it on
        // next
        // use — we deliberately don't docker-run here, to keep startup fast). Only when the branch
        // itself is gone from origin is the work genuinely lost; only then is the workspace
        // ABANDONED
        // (soft-delete, so its history survives). This is now the only path to abandonment.
        for (Workspace existing : workspaceRepository.findActiveByRepositoryId(repoId)) {
          if (containerWorkspaceIds.contains(existing.workspaceId)) {
            continue; // healthy — handled above
          }
          if (existing.branch != null && workspaceService.branchExists(repoId, existing.branch)) {
            existing.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
          } else {
            existing.status = WorkspaceStatus.ABANDONED;
            existing.resolvedAt = Instant.now();
            existing.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
            workspaceEventRepository.persist(
                WorkspaceEvent.builder()
                    .workspace(existing)
                    .type(WorkspaceEventType.ABANDONED)
                    .parent(existing.parent)
                    .at(Instant.now())
                    .build());
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Repository discovery failed", e);
    }
  }
}
