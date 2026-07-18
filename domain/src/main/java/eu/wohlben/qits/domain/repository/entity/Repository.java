package eu.wohlben.qits.domain.repository.entity;

import eu.wohlben.qits.domain.daemon.entity.RepositoryDaemon;
import eu.wohlben.qits.domain.featureflow.entity.ActionConfiguration;
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

  /**
   * The last {@code .qits-config.yml} ingestion problem, if any — a parse or per-entry validation
   * failure, shown as a repository-level warning in the detail view. {@code null} when the file is
   * absent or ingested cleanly. Config ingestion "degrades loudly, never blocks": on any problem
   * the last-good rows are kept and the message lands here instead of failing the clone/sync.
   */
  @Column(name = "config_warning", length = 4000)
  public String configWarning;

  @OneToMany(mappedBy = "repository", cascade = CascadeType.ALL, orphanRemoval = true)
  public List<Workspace> workspaces;

  /** Actions owned by (and only available in) this repository; cascade-deleted with it. */
  @OneToMany(mappedBy = "repository", cascade = CascadeType.ALL, orphanRemoval = true)
  public List<ActionConfiguration> actions;

  /** Daemons owned by (and only available in) this repository; cascade-deleted with it. */
  @OneToMany(mappedBy = "repository", cascade = CascadeType.ALL, orphanRemoval = true)
  public List<RepositoryDaemon> daemons;

  @ManyToOne
  @JoinColumn(name = "project_id", nullable = false)
  public Project project;
}
