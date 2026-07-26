package eu.wohlben.qits.projects.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.entity.Repository;
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

  // SEAM (migration-plan.md §6, repository <-> workspace). The workspace metadata sidecars
  // (write/read/readAll/deleteWorkspaceMetadata over workspace_<id>.json) are cut: WorkspaceMetadata
  // is WS_REPO and the only production caller left in this context was ResolveConflictService, which
  // is itself workspaces-shaped and is not carried here. The repository sidecar (repository.json),
  // which repository discovery reads to restore url/archetype, is unaffected and stays.

  String getDataDir() {
    return dataDir;
  }

  private Path getMetadataDir(String repoId) {
    return Path.of(dataDir, repoId, "metadata");
  }
}
