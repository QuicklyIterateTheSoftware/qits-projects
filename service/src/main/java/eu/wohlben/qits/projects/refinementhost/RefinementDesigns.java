package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.RefinementDesign;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.RefinementDesignRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The refinement's frozen HTML designs — the Design tab's rows, held host-side so they outlive the
 * container, and the surface an agent proposes revisions on.
 *
 * <p>Two kinds of write, and the difference is who decides. {@link #add} stores what a person
 * captured, ACTIVE straight away; {@link #propose} stores an agent's revision as PROPOSED and
 * nothing more happens until {@link #resolve} — an agent can never make its own design the one on
 * show.
 *
 * <p>The document is not read for meaning here: it is stored whole, measured in UTF-8 bytes, and
 * never rendered by this process. The size cap is the only judgement passed on it.
 */
@ApplicationScoped
public class RefinementDesigns {

  /** What a person may do with a proposal. Discarding it is an ordinary delete. */
  public enum Resolution {
    /** Copy the proposal onto the design it revises, then drop the proposal. */
    REPLACE,
    /** Keep the proposal as a design of its own. */
    KEEP
  }

  @Inject RefinementDesignRepository store;

  @Inject RefinementChangePublisher changes;

  @ConfigProperty(name = "qits.projects.refinement-design-max-bytes", defaultValue = "4194304")
  long maxBytes;

  /** Oldest first — the order the tab renders. */
  public List<RefinementDesign> list(long refinementId) {
    return QuarkusTransaction.requiringNew().call(() -> store.listByRefinement(refinementId));
  }

  public RefinementDesign get(long refinementId, String designId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> store.findByRefinementAndId(refinementId, designId))
        .orElseThrow(() -> new NotFoundException("No such design"));
  }

  /** Store a captured design. It is ACTIVE at once — a person captured it. */
  public RefinementDesign add(
      long refinementId,
      String title,
      String html,
      String sourceRoute,
      boolean truncated,
      String createdBy) {
    requireTitle(title);
    int bytes = measure(html);
    Instant now = Instant.now();
    RefinementDesign row = new RefinementDesign();
    row.id = UUID.randomUUID().toString();
    row.refinementId = refinementId;
    row.title = title;
    row.status = RefinementDesign.Status.ACTIVE;
    row.html = html;
    row.htmlBytes = bytes;
    row.truncated = truncated;
    row.sourceRoute = sourceRoute;
    row.createdBy = createdBy;
    row.createdAt = now;
    row.updatedAt = now;
    QuarkusTransaction.requiringNew().run(() -> store.persist(row));
    changes.fire(refinementId, RefinementChangeHint.Topic.DESIGNS);
    return row;
  }

  /**
   * Store an agent's proposal. Naming a base makes it a revision of that design; without one it
   * stands on its own. Either way it waits as PROPOSED.
   */
  public RefinementDesign propose(
      long refinementId,
      String title,
      String html,
      String note,
      String basedOnDesignId,
      String createdBy) {
    requireTitle(title);
    int bytes = measure(html);
    Instant now = Instant.now();
    RefinementDesign row =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  if (basedOnDesignId != null && !basedOnDesignId.isBlank()) {
                    RefinementDesign base =
                        store
                            .findByRefinementAndId(refinementId, basedOnDesignId)
                            .orElseThrow(
                                () ->
                                    new DomainException(
                                        400, "No design " + basedOnDesignId + " to revise."));
                    if (base.status != RefinementDesign.Status.ACTIVE) {
                      throw new DomainException(
                          400, "A proposal can only revise an active design.");
                    }
                  }
                  RefinementDesign fresh = new RefinementDesign();
                  fresh.id = UUID.randomUUID().toString();
                  fresh.refinementId = refinementId;
                  fresh.title = title;
                  fresh.status = RefinementDesign.Status.PROPOSED;
                  fresh.basedOnDesignId =
                      (basedOnDesignId == null || basedOnDesignId.isBlank())
                          ? null
                          : basedOnDesignId;
                  fresh.note = note;
                  fresh.html = html;
                  fresh.htmlBytes = bytes;
                  fresh.truncated = false;
                  fresh.createdBy = createdBy;
                  fresh.createdAt = now;
                  fresh.updatedAt = now;
                  store.persist(fresh);
                  return fresh;
                });
    changes.fire(refinementId, RefinementChangeHint.Topic.DESIGNS);
    return row;
  }

  /**
   * The person's decision on a proposal. REPLACE overwrites the design it revises and drops the
   * proposal; KEEP turns the proposal into a design of its own. Discarding is a plain delete.
   */
  public RefinementDesign resolve(long refinementId, String designId, Resolution mode) {
    RefinementDesign settled =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  RefinementDesign proposal =
                      store
                          .findByRefinementAndId(refinementId, designId)
                          .orElseThrow(() -> new NotFoundException("No such design"));
                  if (proposal.status != RefinementDesign.Status.PROPOSED) {
                    throw new DomainException(409, "This design is not a proposal.");
                  }
                  if (mode == Resolution.KEEP) {
                    proposal.status = RefinementDesign.Status.ACTIVE;
                    proposal.note = null;
                    proposal.updatedAt = Instant.now();
                    return proposal;
                  }
                  RefinementDesign base =
                      proposal.basedOnDesignId == null
                          ? null
                          : store
                              .findByRefinementAndId(refinementId, proposal.basedOnDesignId)
                              .orElse(null);
                  if (base == null) {
                    throw new DomainException(400, "This proposal has no original to replace");
                  }
                  base.html = proposal.html;
                  base.htmlBytes = proposal.htmlBytes;
                  base.truncated = proposal.truncated;
                  base.sourceRoute = proposal.sourceRoute;
                  base.updatedAt = Instant.now();
                  store.delete(proposal);
                  return base;
                });
    changes.fire(refinementId, RefinementChangeHint.Topic.DESIGNS);
    return settled;
  }

  public RefinementDesign rename(long refinementId, String designId, String title) {
    requireTitle(title);
    RefinementDesign renamed =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  RefinementDesign row =
                      store
                          .findByRefinementAndId(refinementId, designId)
                          .orElseThrow(() -> new NotFoundException("No such design"));
                  row.title = title;
                  row.updatedAt = Instant.now();
                  return row;
                });
    changes.fire(refinementId, RefinementChangeHint.Topic.DESIGNS);
    return renamed;
  }

  public void delete(long refinementId, String designId) {
    boolean removed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    store
                        .findByRefinementAndId(refinementId, designId)
                        .map(
                            row -> {
                              store.delete(row);
                              return true;
                            })
                        .orElse(false));
    if (!removed) {
      throw new NotFoundException("No such design");
    }
    changes.fire(refinementId, RefinementChangeHint.Topic.DESIGNS);
  }

  private static void requireTitle(String title) {
    if (title == null || title.isBlank()) {
      throw new DomainException(400, "A design needs a title.");
    }
  }

  /** UTF-8 length, which is what the cap counts and what the row records. */
  private int measure(String html) {
    if (html == null || html.isBlank()) {
      throw new DomainException(400, "A design needs its HTML.");
    }
    int bytes = html.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > maxBytes) {
      throw new DomainException(413, "The design is larger than " + maxBytes + " bytes.");
    }
    return bytes;
  }
}
