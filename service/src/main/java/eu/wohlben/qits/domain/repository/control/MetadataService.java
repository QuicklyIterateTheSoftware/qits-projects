package eu.wohlben.qits.domain.repository.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.domain.repository.entity.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MetadataService {

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @Inject ObjectMapper objectMapper;

  public void writeRepositoryMetadata(Repository repo) {
    try {
      Path metadataPath = getMetadataDir(repo.id);
      Files.createDirectories(metadataPath);
      RepositoryMetadata metadata = new RepositoryMetadata();
      metadata.id = repo.id;
      metadata.url = repo.url;
      metadata.archetype = repo.archetype;
      objectMapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(metadataPath.resolve("repository.json").toFile(), metadata);
    } catch (IOException e) {
      throw new RuntimeException("Failed to write repository metadata for " + repo.id, e);
    }
  }

  public Optional<RepositoryMetadata> readRepositoryMetadata(String repoId) {
    Path file = getMetadataDir(repoId).resolve("repository.json");
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(file.toFile(), RepositoryMetadata.class));
    } catch (IOException e) {
      throw new RuntimeException("Failed to read repository metadata for " + repoId, e);
    }
  }

  public void writeWorktreeMetadata(String repoId, WorktreeMetadata wt) {
    try {
      Path metadataPath = getMetadataDir(repoId);
      Files.createDirectories(metadataPath);
      objectMapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(metadataPath.resolve("worktree_" + wt.worktreeId + ".json").toFile(), wt);
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to write worktree metadata for " + repoId + "/" + wt.worktreeId, e);
    }
  }

  public Optional<WorktreeMetadata> readWorktreeMetadata(String repoId, String worktreeId) {
    Path file = getMetadataDir(repoId).resolve("worktree_" + worktreeId + ".json");
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(file.toFile(), WorktreeMetadata.class));
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to read worktree metadata for " + repoId + "/" + worktreeId, e);
    }
  }

  public List<WorktreeMetadata> readAllWorktreeMetadata(String repoId) {
    List<WorktreeMetadata> result = new ArrayList<>();
    Path metadataPath = getMetadataDir(repoId);
    if (!Files.exists(metadataPath)) {
      return result;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(metadataPath, "worktree_*.json")) {
      for (Path file : stream) {
        result.add(objectMapper.readValue(file.toFile(), WorktreeMetadata.class));
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to read worktree metadata for " + repoId, e);
    }
    return result;
  }

  public void deleteWorktreeMetadata(String repoId, String worktreeId) {
    try {
      Path file = getMetadataDir(repoId).resolve("worktree_" + worktreeId + ".json");
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to delete worktree metadata for " + repoId + "/" + worktreeId, e);
    }
  }

  String getDataDir() {
    return dataDir;
  }

  private Path getMetadataDir(String repoId) {
    return Path.of(dataDir, repoId, "metadata");
  }
}
