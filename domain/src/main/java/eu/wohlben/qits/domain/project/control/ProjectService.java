package eu.wohlben.qits.domain.project.control;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.featureflow.persistence.FeatureFlowConfigurationRepository;
import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.project.persistence.ProjectRepository;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {

  @Inject ProjectRepository projectRepository;

  @Inject RepositoryRepository repositoryRepository;

  @Inject FeatureFlowConfigurationRepository featureFlowConfigurationRepository;

  @Inject RepositoryService repositoryService;

  @Transactional
  public Project create(String name, String description) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("name is required");
    }

    Project project = new Project();
    project.id = UUID.randomUUID().toString();
    project.name = name;
    project.description = description;
    projectRepository.persist(project);

    return project;
  }

  public Project get(String id) {
    return projectRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Project not found: " + id));
  }

  public List<Project> list() {
    return projectRepository.listAll();
  }

  @Transactional
  public Project update(String id, String name, String description) {
    Project project = get(id);

    if (name != null && !name.isBlank()) {
      project.name = name;
    }
    if (description != null) {
      project.description = description;
    }

    return project;
  }

  @Transactional
  public void delete(String id) {
    Project project = get(id);
    // Flow configurations go first: their phase actions may bind repository-scoped actions, and
    // that FK has no cascade — deleting a repository (which cascades its actions) while a flow
    // still binds them would fail.
    featureFlowConfigurationRepository
        .find("project.id", id)
        .list()
        .forEach(featureFlowConfigurationRepository::delete);
    // Delegate to RepositoryService.delete (not a raw row delete) so each repository's containers
    // and on-disk clone are torn down too — otherwise deleting a project (e.g. a seed reset) leaks
    // them as orphans.
    repositoryRepository.find("project.id", id).list().stream()
        .map(r -> r.id)
        .forEach(repositoryService::delete);
    projectRepository.delete(project);
  }

  public List<Repository> getRepositories(String projectId) {
    get(projectId); // verify project exists
    return repositoryRepository.find("project.id", projectId).list();
  }

  @Transactional
  public Repository createRepositoryUnderProject(
      String projectId, String url, RepositoryArchetype archetype) {
    Project project = get(projectId);

    return repositoryService.cloneRepository(url, archetype, project);
  }
}
