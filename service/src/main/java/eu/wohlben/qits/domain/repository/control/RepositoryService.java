package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class RepositoryService {

    @Inject
    RepositoryRepository repositoryRepository;

    @Inject
    MetadataService metadataService;

    @Inject
    GitExecutor git;

    @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
    String dataDir;

    @Transactional
    public Repository cloneRepository(String id, String url, RepositoryArchetype archetype) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException("url is required");
        }
        if (repositoryRepository.findByIdOptional(id).isPresent()) {
            throw new BadRequestException("Repository already exists: " + id);
        }
        Repository repo = new Repository();
        repo.id = id;
        repo.url = url;
        repo.archetype = archetype != null ? archetype : RepositoryArchetype.SERVICE;
        repositoryRepository.persist(repo);

        Path originPath = Path.of(dataDir, id, "origin");
        try {
            Files.createDirectories(originPath.getParent());
            git.exec(null, "git", "clone", "--mirror", url, originPath.toString());
        } catch (Exception e) {
            throw new InternalServerErrorException("Git clone failed: " + e.getMessage());
        }

        metadataService.writeRepositoryMetadata(repo);

        return repo;
    }

    public String pullRepository(String repoId) {
        repositoryRepository.findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

        Path originPath = Path.of(dataDir, repoId, "origin");
        if (!Files.exists(originPath)) {
            throw new NotFoundException("Repository origin not found on disk");
        }

        try {
            return git.exec(originPath.toFile(), "git", "fetch", "--all");
        } catch (Exception e) {
            throw new InternalServerErrorException("Git pull failed: " + e.getMessage());
        }
    }

    public String pushRepository(String repoId) {
        repositoryRepository.findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

        Path originPath = Path.of(dataDir, repoId, "origin");
        if (!Files.exists(originPath)) {
            throw new NotFoundException("Repository origin not found on disk");
        }

        try {
            return git.exec(originPath.toFile(), "git", "push");
        } catch (Exception e) {
            throw new InternalServerErrorException("Git push failed: " + e.getMessage());
        }
    }
}
