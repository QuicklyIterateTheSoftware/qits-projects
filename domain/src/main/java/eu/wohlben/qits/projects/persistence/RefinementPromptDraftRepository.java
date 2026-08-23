package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.RefinementPromptDraft;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * The one-draft-per-refinement table. The upsert is SQL rather than find-then-persist so two
 * autosave races collapse into last-writer-wins instead of a unique-constraint 500 — the same shape
 * qits-workspaces' draft repository has.
 */
@ApplicationScoped
public class RefinementPromptDraftRepository
    implements PanacheRepositoryBase<RefinementPromptDraft, Long> {

  public Optional<RefinementPromptDraft> findByRefinement(Long refinementId) {
    return findByIdOptional(refinementId);
  }

  /** Insert or overwrite the draft, bumping {@code prompt_version} and {@code updated_at}. */
  public void upsert(Long refinementId, String content, String serializedPrompt) {
    getEntityManager().createNativeQuery(
            "insert into refinement_prompt_draft"
                + " (refinement_id_fk, content, serialized_prompt, updated_at)"
                + " values (?1, ?2, ?3, current_timestamp)"
                + " on conflict (refinement_id_fk) do update set"
                + " content = excluded.content,"
                + " serialized_prompt = excluded.serialized_prompt,"
                + " updated_at = current_timestamp")
        .setParameter(1, refinementId)
        .setParameter(2, content)
        .setParameter(3, serializedPrompt)
        .executeUpdate();
    getEntityManager().createNativeQuery(
            "update refinement_prompt_draft set prompt_version = prompt_version + 1"
                + " where refinement_id_fk = ?1")
        .setParameter(1, refinementId)
        .executeUpdate();
  }

  public void deleteByRefinement(Long refinementId) {
    delete("refinementId", refinementId);
  }
}
