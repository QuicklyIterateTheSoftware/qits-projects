package eu.wohlben.qits.projects.refinementhost;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.entity.RefinementPromptDraft;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.persistence.RefinementPromptDraftRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The refinement's unsent prompt — host-owned, container-outliving, opaque. Validation here is
 * well-formedness and size only: the {@code content} schema belongs to the SPA, and reading
 * anything out of it would put one document's schema in two repositories. The same contract
 * qits-workspaces' draft service keeps.
 */
@ApplicationScoped
public class RefinementPromptDrafts {

  @Inject RefinementPromptDraftRepository store;

  @Inject RefinementChangePublisher changes;

  @Inject ObjectMapper objectMapper;

  /** The cap on {@code content} plus {@code serializedPrompt}, bytes of UTF-8. */
  @ConfigProperty(name = "qits.projects.refinement-prompt-draft-max-bytes", defaultValue = "2097152")
  long maxBytes;

  /** The draft, or empty — the controller turns empty into the 404 the SPA reads as "none". */
  public Optional<RefinementPromptDraft> find(long refinementId) {
    return QuarkusTransaction.requiringNew().call(() -> store.findByRefinement(refinementId));
  }

  /** Save (upsert) the draft, and say so on the hint channel — after the write, never inside it. */
  public RefinementPromptDraft save(long refinementId, String content, String serializedPrompt) {
    if (content == null) {
      throw new DomainException(400, "A draft needs content.");
    }
    long size =
        content.getBytes(StandardCharsets.UTF_8).length
            + (serializedPrompt == null
                ? 0
                : serializedPrompt.getBytes(StandardCharsets.UTF_8).length);
    if (size > maxBytes) {
      throw new DomainException(413, "The draft is larger than " + maxBytes + " bytes.");
    }
    try {
      objectMapper
          .copy()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readTree(content);
    } catch (Exception e) {
      throw new DomainException(400, "The draft content is not well-formed JSON.");
    }
    RefinementPromptDraft saved =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  store.upsert(refinementId, content, serializedPrompt);
                  return store.findByRefinement(refinementId).orElseThrow();
                });
    changes.fire(refinementId, RefinementChangeHint.Topic.PROMPT_DRAFT);
    return saved;
  }

  /** Delete the draft. 204-shaped: absence is the asked-for state, never an error. */
  public void delete(long refinementId) {
    QuarkusTransaction.requiringNew().run(() -> store.deleteByRefinement(refinementId));
    changes.fire(refinementId, RefinementChangeHint.Topic.PROMPT_DRAFT);
  }
}
