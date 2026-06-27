package eu.wohlben.qits.domain.repository.entity;

import eu.wohlben.qits.domain.project.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.List;

@Entity
public class Repository extends PanacheEntityBase {

  @Id public String id;

  public String url;

  /** The branch synced with the remote (e.g. "main"/"master"). Configurable per repository. */
  @Column(name = "main_branch")
  public String mainBranch;

  @Enumerated(EnumType.STRING)
  public RepositoryArchetype archetype;

  @OneToMany(mappedBy = "repository", cascade = CascadeType.ALL, orphanRemoval = true)
  public List<Worktree> worktrees;

  @ManyToOne
  @JoinColumn(name = "project_id", nullable = false)
  public Project project;
}
