package eu.wohlben.qits.projects.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class Repository extends PanacheEntityBase {

  @Id public String id;

  public String url;

  /** The branch synced with the remote (e.g. "main"/"master"). Configurable per repository. */
  @Column(name = "main_branch")
  public String mainBranch;

  @Enumerated(EnumType.STRING)
  public RepositoryArchetype archetype;

  // SEAM (migration-plan.md §6): the `workspaces` @OneToMany is gone. The Workspace entity and the
  // `workspace` table belong to qits-workspaces, in a different physical database (§7), so neither
  // a JPA relation nor a foreign key can span the two. Workspaces reach a repository by String id
  // through qits-workspaces' RepositoryLookup port; this context reaches back, when it needs to,
  // through WorkspaceLookup.

  @ManyToOne
  @JoinColumn(name = "project_id", nullable = false)
  public Project project;
}
