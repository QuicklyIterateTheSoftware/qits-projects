package eu.wohlben.qits.domain.repository.mcp;

import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptAttachment;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptDraft;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptAttachmentRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptDraftRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import eu.wohlben.qits.domain.telemetry.mcp.WorkspaceScope;
import io.quarkiverse.mcp.server.Content;
import io.quarkiverse.mcp.server.ImageContent;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * The {@code taskPrompt} tool of the "repository" MCP server: it serves the composed prompt a user
 * built for this workspace on the workspace-detail route — the refined markdown followed by every
 * attached image — so the coding agent <em>fetches</em> its task rather than being pushed it. That
 * inversion is what lets a picture reach every launch shape (chat, interactive PTY, autonomous
 * {@code -p} all support MCP tool results, whose content arrays carry image blocks natively), where
 * a pushed prompt could only carry images on the chat transport.
 *
 * <p>Like the telemetry tools this takes <em>no arguments</em>: the workspace comes entirely from
 * the connection's scope (the {@code X-QITS-Workspace} header / {@code ?workspaceId=} stamped into
 * the MCP URL at agent launch), never a tool argument, so a session cannot read another workspace's
 * prompt. {@link TaskPromptToolFilter} hides the tool from any session not narrowed to a workspace,
 * and every call re-validates the scope.
 *
 * <p>This is the first tool in the codebase to return a {@link ToolResponse} with mixed {@link
 * TextContent} + {@link ImageContent} blocks (the others return a DTO the framework wraps as a
 * single text block): {@link WorkspacePromptDraft#serializedPrompt} becomes the text block and each
 * {@link WorkspacePromptAttachment} becomes an image block.
 */
@ApplicationScoped
@WrapBusinessError
public class TaskPromptMcpTools {

  @Inject ProjectScope projectScope;

  @Inject ProjectScopeGuard scopeGuard;

  @Inject WorkspaceScope workspaceScope;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspacePromptDraftRepository draftRepository;

  @Inject WorkspacePromptAttachmentRepository attachmentRepository;

  @McpServer("repository")
  @Tool(
      description =
          "The current task prompt composed for this workspace: the refined instructions as"
              + " markdown, followed by any images the user attached (sketches, pasted"
              + " screenshots). Call this first, then implement what it describes.")
  @Transactional
  public ToolResponse taskPrompt() {
    Workspace workspace = requireWorkspace();

    Optional<WorkspacePromptDraft> draft = draftRepository.findByWorkspaceId(workspace.id);
    String markdown =
        draft.map(d -> d.serializedPrompt).filter(s -> s != null && !s.isBlank()).orElse(null);
    List<WorkspacePromptAttachment> attachments =
        attachmentRepository.listByWorkspaceId(workspace.id);

    if (markdown == null && attachments.isEmpty()) {
      return ToolResponse.success("No task prompt is currently composed for this workspace.");
    }

    List<Content> content = new ArrayList<>();
    String versionLine =
        "Task prompt" + draft.map(d -> " (updated " + d.updatedAt + ")").orElse("") + ":";
    content.add(
        new TextContent(
            versionLine
                + "\n\n"
                + (markdown != null ? markdown : "(no prompt text composed yet)")));

    for (WorkspacePromptAttachment attachment : attachments) {
      content.add(new TextContent(attachment.label + " (" + attachment.id + "):"));
      if (attachment.bytes == null || attachment.bytes.length == 0) {
        // Degrade a missing/empty attachment to a text note rather than failing the whole call.
        content.add(new TextContent("(image unavailable)"));
      } else {
        content.add(
            new ImageContent(
                Base64.getEncoder().encodeToString(attachment.bytes), attachment.mimeType));
      }
    }
    return ToolResponse.success(content);
  }

  /**
   * Resolves and validates the connection's repository + workspace narrowing (mirrors {@code
   * TelemetryMcpTools.requireScope}), returning the active {@link Workspace} — its surrogate {@code
   * id} keys the draft and attachment rows.
   */
  private Workspace requireWorkspace() {
    String repoId = projectScope.repositoryId().orElseThrow(this::notScoped);
    scopeGuard.requireRepoInProject(repoId);
    String workspaceId = workspaceScope.requireWorkspaceId();
    return workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));
  }

  private RuntimeException notScoped() {
    return new NotFoundException(
        "This MCP session is not narrowed to a repository; the taskPrompt tool needs repository"
            + " and workspace scope.");
  }
}
