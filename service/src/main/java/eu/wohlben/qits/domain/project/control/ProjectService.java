package eu.wohlben.qits.domain.project.control;

import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.project.persistence.ProjectRepository;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.util.List;

@ApplicationScoped
public class ProjectService {

    @Inject
    ProjectRepository projectRepository;

    @Inject
    RepositoryRepository repositoryRepository;

    @Inject
    RepositoryService repositoryService;

    @Transactional
    public Project create(String id, String name, String description) {
        if (id == null || id.isBlank()) {
            throw new BadRequestException("id is required");
        }
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (projectRepository.findByIdOptional(id).isPresent()) {
            throw new BadRequestException("Project already exists: " + id);
        }

        Project project = new Project();
        project.id = id;
        project.name = name;
        project.description = description;
        projectRepository.persist(project);

        return project;
    }

    public Project get(String id) {
        return projectRepository.findByIdOptional(id)
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
        repositoryRepository.find("project.id", id).list()
            .forEach(r -> r.project = null);
        projectRepository.delete(project);
    }

    public List<Repository> getRepositories(String projectId) {
        get(projectId); // verify project exists
        return repositoryRepository.find("project.id", projectId).list();
    }

    @Transactional
    public Repository createRepositoryUnderProject(String projectId, String repoId, String url, RepositoryArchetype archetype) {
        get(projectId); // verify project exists

        Repository repo = repositoryService.cloneRepository(repoId, url, archetype);
        return associateRepository(projectId, repoId);
    }

    @Transactional
    public Repository associateRepository(String projectId, String repositoryId) {
        Project project = get(projectId);
        Repository repo = repositoryRepository.findByIdOptional(repositoryId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repositoryId));

        if (repo.project != null && !repo.project.id.equals(projectId)) {
            throw new BadRequestException("Repository already associated with another project");
        }

        repo.project = project;
        return repo;
    }

    @Transactional
    public Repository disassociateRepository(String projectId, String repositoryId) {
        Project project = get(projectId);
        Repository repo = repositoryRepository.findByIdOptional(repositoryId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repositoryId));

        if (repo.project == null || !repo.project.id.equals(projectId)) {
            throw new NotFoundException("Repository not associated with this project");
        }

        repo.project = null;
        return repo;
    }
}
