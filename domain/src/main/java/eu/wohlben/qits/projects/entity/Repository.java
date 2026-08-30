package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.util.UUID;

/**
 * A git remote as an entity: one bare origin on disk, its backup twin, and the row that names both.
 *
 * <p><b>A {@link CausedRow}, and the one in this context worth tracing.</b> Rows are minted by four
 * paths and all four run on a request thread — {@code cloneOne}, {@code createBlankRepository},
 * {@code initWrapperOrigin} and {@code adoptExistingOrigin} — so the {@code CausationStamp}
 * listener reads the scope the {@code X-Qits-Causation-Id} filter restored. The machine-driven one
 * is {@code WrapperReconcileService}: a reconcile called with a cause records, per adopted or cloned
 * member, what asked for it. A browser-driven create carries no header and is rootless, and so is
 * every row the boot-time self-seed writes — neither is a hole, both are "nothing caused this".
 *
 * <p>Nothing sets the value explicitly, because there is no hop to cross: the backup executor and
 * the pull executor UPDATE these rows and never insert one, and the stamp is insert-only.
 */
@Entity
@EntityListeners(CausationStamp.class)
public class Repository extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  public String url;

  /** The branch synced with the remote (e.g. "main"/"master"). Configurable per repository. */
  @Column(name = "main_branch")
  public String mainBranch;

  @Enumerated(EnumType.STRING)
  public RepositoryArchetype archetype;

  /**
   * The technical component this repository is part of — the second segment of its wrapper path
   * under the component layout ({@code components/<component>/<repo>}), and null for an entry still
   * mounted under one of the six archetype directories.
   *
   * <p>An <b>open set</b>, unlike {@link #archetype}: the wrapper names components and this column
   * records what it named, so there is no enum and no check constraint (V6). Null is a real state
   * and stays one for as long as a wrapper has entries the flip has not reached.
   */
  public String component;

  /**
   * The last committed-configuration problem, or null when there is none. Config ingestion degrades
   * loudly and never blocks, so a disagreement lands here rather than changing the row: the wrapper
   * directory is what decides {@link #archetype}, and a {@code repository.yml} that says otherwise
   * is a message to its author.
   */
  @Column(name = "config_warning", length = 4000)
  public String configWarning;

  /**
   * When this repository was last backed up to its forge twin — attempted, not necessarily
   * succeeded. Null means never attempted, which is a different thing from failing and reads that
   * way everywhere.
   */
  @Column(name = "last_backup_at")
  public java.time.Instant lastBackupAt;

  /** How that attempt went. Null exactly when {@link #lastBackupAt} is. */
  @Enumerated(EnumType.STRING)
  @Column(name = "last_backup_outcome")
  public BackupOutcome lastBackupOutcome;

  /** The short human line behind a non-success outcome; cleared by a success. */
  @Column(name = "last_backup_detail", length = 1000)
  public String lastBackupDetail;

  // SEAM (migration-plan.md §6): the `workspaces` @OneToMany is gone. The Workspace entity and the
  // `workspace` table belong to qits-workspaces, in a different physical database (§7), so neither
  // a JPA relation nor a foreign key can span the two. Workspaces reach a repository by String id
  // through qits-workspaces' RepositoryLookup port; this context reaches back, when it needs to,
  // through WorkspaceLookup.

  @ManyToOne
  @JoinColumn(name = "project_id", nullable = false)
  public Project project;
}
