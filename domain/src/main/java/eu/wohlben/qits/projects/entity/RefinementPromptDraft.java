package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The unsent prompt a refinement's chat panel holds — host-owned so it survives the container, a
 * browser reload, and a container recreate. The projects twin of qits-workspaces'
 * {@code WorkspacePromptDraft}, keyed by the refinement row instead of a workspace.
 *
 * <p><b>{@code content} is an opaque JSON blob whose schema the SPA owns.</b> The host validates
 * well-formedness and size and stores bytes; reading anything out of it here would put one
 * document's schema in two repositories.
 *
 * <p><b>{@code @Uncaused}</b>, like the workspaces twin: a draft autosave fires on a timer in the
 * browser, is overwritten in place, and traces to no domain event worth recording.
 */
@Entity
@Table(name = "refinement_prompt_draft")
@Uncaused
public class RefinementPromptDraft extends PanacheEntityBase {

  /** Shared primary key: the refinement's row id. One draft per refinement. */
  @Id
  @Column(name = "refinement_id_fk")
  public Long refinementId;

  @Column(name = "content", nullable = false, columnDefinition = "text")
  public String content;

  @Column(name = "serialized_prompt", columnDefinition = "text")
  public String serializedPrompt;

  /** Bumped on every save; the SPA's echo-dedup key beside {@link #updatedAt}. */
  @Column(name = "prompt_version", nullable = false)
  public long promptVersion;

  @Column(name = "last_run_at")
  public Instant lastRunAt;

  @Column(name = "last_run_prompt_version")
  public Long lastRunPromptVersion;

  @Column(name = "last_run_command_id")
  public String lastRunCommandId;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
