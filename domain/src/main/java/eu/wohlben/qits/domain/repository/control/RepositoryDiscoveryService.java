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

        List<WorktreeMetadata> worktreeMetadataList =
            metadataService.readAllWorktreeMetadata(repoId);
        Set<String> metadataWorktreeIds = new HashSet<>();
        for (WorktreeMetadata wtMeta : worktreeMetadataList) {
          metadataWorktreeIds.add(wtMeta.worktreeId);
          // Upsert against the ACTIVE row only, so a resolved worktree of the same id is never
          // revived back to ACTIVE; a metadata file with no active row means a fresh worktree.
          Worktree worktree =
              worktreeRepository
                  .findActiveByRepositoryAndWorktreeId(repoId, wtMeta.worktreeId)
                  .orElseGet(
                      () -> {
                        Worktree w = new Worktree();
                        w.worktreeId = wtMeta.worktreeId;
                        w.repository = repo;
                        w.status = WorktreeStatus.ACTIVE;
                        worktreeRepository.persist(w);
                        worktreeEventRepository.persist(
                            WorktreeEvent.builder()
                                .worktree(w)
                                .type(WorktreeEventType.CREATED)
                                .parent(wtMeta.parent)
                                .at(Instant.now())
                                .build());
                        return w;
                      });
          worktree.parent = wtMeta.parent;
          worktreeRepository.persist(worktree);
        }

        // Soft-delete: an ACTIVE worktree whose metadata vanished (removed out-of-band) is marked
        // ABANDONED, not deleted, so its history survives. Resolved rows are left untouched.
        for (Worktree existing : worktreeRepository.findActiveByRepositoryId(repoId)) {
          if (!metadataWorktreeIds.contains(existing.worktreeId)) {
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
