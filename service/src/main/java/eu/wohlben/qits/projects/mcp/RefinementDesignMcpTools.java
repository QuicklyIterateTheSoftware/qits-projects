package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.entity.RefinementDesign;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projects.refinementhost.RefinementDesigns;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * The design half of the "repository" MCP server — the surface a refinement agent reads the epic's
 * frozen HTML designs on and proposes revisions to, mounted on the same declared server as {@link
 * EpicMcpTools} for the reason stated there.
 *
 * <p><strong>The agent proposes; a person decides.</strong> There is no tool that makes a design
 * ACTIVE, and there must not be one: a proposal sits in the UI until somebody replaces the original
 * with it, keeps it alongside, or discards it. The model is told so in the tool's own description,
 * so it does not wait for an acceptance it can never observe.
 *
 * <p>Scope is the epic's refinement, resolved from {@link ProjectScope} exactly as the epic tools
 * resolve theirs: an epic in another project, and an epic with no refinement open, are the same
 * answer — nothing here says what another project holds.
 *
 * <p><strong>No {@code @Transactional}</strong>, for the identical reason {@link EpicMcpTools}
 * carries none: these tools straddle two non-XA persistence units, and one transaction cannot
 * enlist both.
 */
@ApplicationScoped
@WrapBusinessError
public class RefinementDesignMcpTools {

  /** What a write with no forwarded identity records as its author. */
  private static final String AGENT = "mcp-agent";

  @Inject ProjectScope scope;

  @Inject RefinementRepository refinements;

  @Inject RefinementDesigns designs;

  @Inject SecurityIdentity identity;

  // --- Result shapes --------------------------------------------------------

  /** A design as it appears in a list: everything but the document itself. */
  public record DesignSummary(
      String id,
      String title,
      String status,
      String basedOnDesignId,
      String note,
      String sourceRoute,
      int htmlBytes,
      boolean truncated,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  /** One design with its whole document. */
  public record DesignDetail(
      String id,
      String title,
      String status,
      String basedOnDesignId,
      String note,
      String sourceRoute,
      int htmlBytes,
      boolean truncated,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      String html) {}

  // --- Tools ----------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "list_designs",
      description =
          "List the frozen HTML designs kept with this epic's refinement, oldest first, without"
              + " their documents. ACTIVE is what the person sees in the Design tab; PROPOSED is a"
              + " proposal of yours awaiting the person's decision. Read a design with get_design"
              + " before proposing a revision of it, so what you send back is a revision of what"
              + " actually exists.")
  public List<DesignSummary> listDesigns(
      @ToolArg(description = "id of an epic in this project") String epicId) {
    Refinement refinement = requireRefinementOfEpicInProject(epicId);
    return designs.list(refinement.id).stream()
        .map(RefinementDesignMcpTools::summarize)
        .toList();
  }

  @McpServer("repository")
  @Tool(
      name = "get_design",
      description =
          "Read one design in full: the complete self-contained HTML document, styles inline and"
              + " no scripts. This is the shape a design has — send the same shape back when you"
              + " propose a revision of it.")
  public DesignDetail getDesign(
      @ToolArg(description = "id of an epic in this project") String epicId,
      @ToolArg(description = "id of a design of this epic's refinement") String designId) {
    Refinement refinement = requireRefinementOfEpicInProject(epicId);
    RefinementDesign row = designs.get(refinement.id, designId);
    return new DesignDetail(
        row.id,
        row.title,
        row.status.name(),
        row.basedOnDesignId,
        row.note,
        row.sourceRoute,
        row.htmlBytes,
        row.truncated,
        row.createdBy,
        row.createdAt,
        row.updatedAt,
        row.html);
  }

  @McpServer("repository")
  @Tool(
      name = "propose_design",
      description =
          "Propose a design for this epic's refinement — a new one, or a revision of an existing"
              + " design named by basedOnDesignId. Send a COMPLETE HTML document with inline"
              + " styles, the same shape get_design returns. The person decides in the UI whether"
              + " your proposal replaces the original, is kept as a design of its own, or is"
              + " discarded; you cannot accept it yourself, so do not wait for it to become"
              + " active. Fails with a message when the epic has no open refinement.")
  public DesignSummary proposeDesign(
      @ToolArg(description = "id of an epic in this project") String epicId,
      @ToolArg(description = "short label for the Design tab's list") String title,
      @ToolArg(description = "the complete HTML document, styles inline, no scripts") String html,
      @ToolArg(description = "why you are proposing this — the person reads it") String note,
      @ToolArg(
              required = false,
              description = "id of the ACTIVE design this revises; omit for a design of its own")
          String basedOnDesignId) {
    Refinement refinement = requireRefinementOfEpicInProject(epicId);
    return summarize(
        designs.propose(refinement.id, title, html, note, basedOnDesignId, changedBy()));
  }

  // --- Scoping --------------------------------------------------------------

  /**
   * The refinement of {@code epicId} within the scoped project. An epic in another project and an
   * epic with no refinement open answer the same way — the model is told nothing about what other
   * projects hold.
   */
  private Refinement requireRefinementOfEpicInProject(String epicId) {
    Refinement refinement =
        QuarkusTransaction.requiringNew()
            .call(() -> refinements.findByEpic(epicId))
            .orElseThrow(() -> noRefinement(epicId));
    if (!scope.requireProjectId().equals(refinement.projectId)) {
      throw noRefinement(epicId);
    }
    return refinement;
  }

  private static NotFoundException noRefinement(String epicId) {
    return new NotFoundException("No refinement is open for epic " + epicId);
  }

  // --- Plumbing -------------------------------------------------------------

  /** The row's {@code created_by}: the forwarded user, else the agent marker. */
  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }

  private static DesignSummary summarize(RefinementDesign row) {
    return new DesignSummary(
        row.id,
        row.title,
        row.status.name(),
        row.basedOnDesignId,
        row.note,
        row.sourceRoute,
        row.htmlBytes,
        row.truncated,
        row.createdBy,
        row.createdAt,
        row.updatedAt);
  }
}
