package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.Worktree;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorktreeRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class RepositoryDiscoveryService {

    private static final Logger LOG = Logger.getLogger(RepositoryDiscoveryService.class);

    @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
    String dataDir;

    @Inject
    MetadataService metadataService;

    @Inject
    RepositoryRepository repositoryRepository;

    @Inject
    WorktreeRepository worktreeRepository;

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
                Repository repo = repositoryRepository.findByIdOptional(repoId).orElseGet(() -> {
                    Repository r = new Repository();
                    r.id = repoId;
                    return r;
                });

                if (metadataOpt.isPresent()) {
                    RepositoryMetadata metadata = metadataOpt.get();
                    repo.url = metadata.url;
                    repo.archetype = metadata.archetype;
                }
                repositoryRepository.persist(repo);

                List<WorktreeMetadata> worktreeMetadataList = metadataService.readAllWorktreeMetadata(repoId);
                Set<String> metadataWorktreeIds = new HashSet<>();
                for (WorktreeMetadata wtMeta : worktreeMetadataList) {
                    metadataWorktreeIds.add(wtMeta.worktreeId);
                    Worktree worktree = worktreeRepository.findByRepositoryAndWorktreeId(repoId, wtMeta.worktreeId)
                        .orElseGet(() -> {
                            Worktree w = new Worktree();
                            w.worktreeId = wtMeta.worktreeId;
                            w.repository = repo;
                            return w;
                        });
                    worktree.parent = wtMeta.parent;
                    worktreeRepository.persist(worktree);
                }

                List<Worktree> existingWorktrees = worktreeRepository.findByRepositoryId(repoId);
                for (Worktree existing : existingWorktrees) {
                    if (!metadataWorktreeIds.contains(existing.worktreeId)) {
                        worktreeRepository.delete(existing);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Repository discovery failed", e);
        }
    }
}
