package eu.wohlben.qits.projects.refinementhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.projects.entity.Refinement;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The env contract a refinement container is started with. Every {@code QITS_WORKSPACE_DAEMON_*}
 * name below is qits-workspace-daemon's own environment table — getting one wrong fails silently
 * (no url leaves the daemon idle, no token leaves its API unbound), which is why this test spells
 * them as literals rather than reading the constants back.
 */
@QuarkusTest
public class RefinementContainerFactoryTest {

  @Inject RefinementContainerFactory factory;
  @Inject FakeRefinementCredentials credentials;

  @BeforeEach
  void reset() {
    credentials.reset();
  }

  private Refinement refinement() {
    Refinement refinement = new Refinement();
    refinement.id = 7L;
    refinement.epicId = "epic-1";
    refinement.projectId = "project-1";
    refinement.repositoryId = "repo-1";
    refinement.branch = "refining/sharper-onboarding";
    refinement.parent = "main";
    refinement.label = "refining-sharper-onboarding";
    refinement.createdAt = Instant.now();
    return refinement;
  }

  @Test
  public void theSpecCarriesTheWholeDialHomeContract() {
    EnsureRequest request =
        factory.forExistingContainer(refinement(), "demo", "sharper-onboarding", "demo-demo");
    Map<String, String> env = request.spec().env();

    assertEquals("ws://qits-projects:8080/projects/refinement-daemon/7", env.get("QITS_WORKSPACE_DAEMON_URL"));
    assertEquals("/projects/refinement-container/7/", env.get("QITS_WORKSPACE_DAEMON_API_BASE_PATH"));
    assertEquals("refining-sharper-onboarding", env.get("QITS_WORKSPACE_DAEMON_WORKSPACE_ID"));
    assertEquals("repo-1", env.get("QITS_WORKSPACE_DAEMON_REPOSITORY_ID"));
    assertEquals("refining/sharper-onboarding", env.get("QITS_WORKSPACE_DAEMON_BRANCH"));
    assertEquals("main", env.get("QITS_WORKSPACE_DAEMON_PARENT"));
    // Both halves of the name-addressed clone — relative submodule urls depend on them.
    assertEquals("project-1", env.get("QITS_WORKSPACE_DAEMON_PROJECT_ID"));
    assertEquals("demo-demo", env.get("QITS_WORKSPACE_DAEMON_REPO_NAME"));
    assertEquals("http://githost.dev.internal:8080/git", env.get("QITS_WORKSPACE_DAEMON_GIT_BASE_URL"));
    // A refinement runs no code: no bootstrap chain and no service autostart, structurally.
    assertEquals("false", env.get("QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN"));
    assertEquals("false", env.get("QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART"));
    assertFalse(env.containsKey("QITS_WORKSPACE_DAEMON_SERVICE_PROXY_BASE"));
    assertFalse(env.get("QITS_WORKSPACE_DAEMON_API_TOKEN").isBlank());
    // Two MCP servers, and no actions server — there is no actions surface on this route.
    assertEquals("http://qits-projects:8080/projects/mcp", env.get("QITS_REPOSITORY_MCP_URL"));
    assertTrue(env.get("QITS_OBSERVABILITY_MCP_URL").endsWith("/observability/mcp"));
    assertFalse(env.containsKey("QITS_ACTIONS_MCP_URL"));
    // The shared credential home, under the daemon's own qits.workspace.-prefixed key.
    assertEquals("/claude-home", env.get("QITS_WORKSPACE_CLAUDE_MOUNT"));

    assertEquals("qits-ref-demo-sharper-onboarding", request.spec().explicitName());
    assertEquals(1, request.spec().volumeMounts().size());
    assertEquals("qits_refinement_7", request.spec().volumeMounts().get(0).volumeName());
    assertEquals("/workspace", request.spec().volumeMounts().get(0).containerPath());
    assertFalse(request.spec().hostDockerSocket());
    assertTrue(request.spec().init());
    assertEquals(Recreate.ifChanged, request.recreate());
  }

  @Test
  public void noIdpMeansNoCredentialBlockAtAll() {
    EnsureRequest request =
        factory.forExistingContainer(refinement(), "demo", "sharper-onboarding", "demo-demo");
    Map<String, String> env = request.spec().env();
    assertFalse(env.containsKey("QITS_COMMISSIONED_CLIENT_ID"));
    assertFalse(env.containsKey("QITS_COMMISSIONED_CLIENT_SECRET"));
    assertFalse(env.containsKey("QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL"));
    assertFalse(env.containsKey("QITS_GIT_AUTH_HOST"));
  }

  @Test
  public void theWakeArmSendsTheRowsPairByteForByte() {
    credentials.enable(true);
    Refinement refinement = refinement();
    refinement.commissionedClientId = "dyn-refinement-7-1";
    refinement.commissionedClientSecret = "secret-dyn-refinement-7-1";
    EnsureRequest request =
        factory.forExistingContainer(refinement, "demo", "sharper-onboarding", "demo-demo");
    Map<String, String> env = request.spec().env();
    assertEquals("dyn-refinement-7-1", env.get("QITS_COMMISSIONED_CLIENT_ID"));
    assertEquals("secret-dyn-refinement-7-1", env.get("QITS_COMMISSIONED_CLIENT_SECRET"));
    assertEquals("qits-projects", env.get("QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE"));
    assertEquals("githost.dev.internal:8080", env.get("QITS_GIT_AUTH_HOST"));
    assertEquals("qits-githost", env.get("QITS_GIT_AUTH_AUDIENCE"));
    assertEquals("/etc/qits-gitconfig", env.get("GIT_CONFIG_GLOBAL"));
  }
}
