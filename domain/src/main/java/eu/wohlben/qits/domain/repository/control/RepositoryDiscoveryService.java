package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.Worktree;
import eu.wohlben.qits.domain.repository.entity.WorktreeEvent;
import eu.wohlben.qits.domain.repository.entity.WorktreeEventType;
import eu.wohlben.qits.domain.repository.entity.WorktreeStatus;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorktreeEventRepository;
import eu.wohlben.qits.domain.repository.persistence.WorktreeRepository;
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

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorktreeRepository worktreeRepository;

  @Inject WorktreeEventRepository worktreeEventRepository;

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

        // Worktrees are now containers, not on-disk checkouts, so reconcile the DB against the
        // live containers (keyed by their qits.* labels) rather than the metadata sidecar files.
        List<ContainerRuntime.ContainerInfo> containerList =
            containers.listWorktreeContainers(repoId);
        Set<String> containerWorktreeIds = new HashSet<>();
        for (ContainerRuntime.ContainerInfo info : containerList) {
          containerWorktreeIds.add(info.worktreeId());
          // Upsert against the ACTIVE row only, so a resolved worktree of the same id is never
          // revived back to ACTIVE; a container with no active row means a fresh worktree whose row
          // was lost (rebuild it from the labels).
          Worktree worktree =
              worktreeRepository
                  .findActiveByRepositoryAndWorktreeId(repoId, info.worktreeId())
                  .orElseGet(
                      () -> {
                        Worktree w = new Worktree();
                        w.worktreeId = info.worktreeId();
                        w.repository = repo;
                        w.status = WorktreeStatus.ACTIVE;
                        worktreeRepository.persist(w);
                        worktreeEventRepository.persist(
                            WorktreeEvent.builder()
                                .worktree(w)
                                .type(WorktreeEventType.CREATED)
                                .branch(info.branch())
                                .parent(info.parent())
                                .at(Instant.now())
                                .build());
                        return w;
                      });
          worktree.parent = info.parent();
          worktree.branch = info.branch();
          worktreeRepository.persist(worktree);
        }

        // Soft-delete: an ACTIVE worktree whose container is gone (removed out-of-band) is marked
        // ABANDONED, not deleted, so its history survives. Resolved rows are left untouched.
        for (Worktree existing : worktreeRepository.findActiveByRepositoryId(repoId)) {
          if (!containerWorktreeIds.contains(existing.worktreeId)) {
            existing.status = WorktreeStatus.ABANDONED;
            existing.resolvedAt = Instant.now();
            worktreeEventRepository.persist(
                WorktreeEvent.builder()
                    .worktree(existing)
                    .type(WorktreeEventType.ABANDONED)
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
