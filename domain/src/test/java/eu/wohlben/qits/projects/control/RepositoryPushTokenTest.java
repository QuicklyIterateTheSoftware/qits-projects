package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The git host's push-token bypass ({@code -o qits.token=<value>}, qits-githost's {@code
 * ProtectedRefHook}), configured here under the SAME key that hook reads
 * ({@code qits.repositories.git.push-token}) so a deployment sets one value once. Its own {@code
 * @TestProfile} because the token has to be a real, non-blank value for these two cases — every
 * other suite runs with it unset (the shipped default), which is what {@link
 * RepositoryServiceTest#aBranchDeleteTheHostRefusesSurfacesAsA4xx} and {@link
 * RepositoryServiceTest#aTokenlessPullAgainstAProtectedDefaultBranchSurfacesTheRefusalNonSilently}
 * already prove.
 *
 * <p>Two things a configured token must do, proved together because they are the same guarantee
 * seen from both sides: a pull that needs to advance a protected default branch presents it and
 * succeeds, and a branch delete — whose refusal on the default branch is intended, proven-live
 * behaviour — never presents it and stays refused, even though a token IS configured.
 */
@QuarkusTest
@TestProfile(RepositoryPushTokenTest.WithPushToken.class)
public class RepositoryPushTokenTest {

  static final String TOKEN = "test-push-token";

  public static class WithPushToken implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.repositories.git.push-token", TOKEN);
    }
  }

  @Inject RepositoryService repositoryService;
  @Inject ProjectService projectService;
  @Inject GitExecutor git;
  @Inject GitHostAddress gitHost;
  @Inject FakeGitHostRepositories fakeGitHostRepositories;

  private Path hostOf(String repoId) {
    return Path.of(gitHost.fetchUrl(repoId));
  }

  @Test
  public void aTokenCarryingPullAdvancesAProtectedDefaultBranch() throws Exception {
    var project = projectService.create("Push Token Pull", null);
    var repo = repositoryService.cloneRepository(GitFixtures.path("testing-repo.git"), null, project);
    fakeGitHostRepositories.protectDefaultBranch(repo.id, TOKEN);

    // Rewind the HOST's branch so the remote (the fixture, untouched) is strictly ahead — the pull
    // must fast-forward the protected branch, which ProtectedRefHook refuses without a matching
    // token and accepts with one.
    String fullSha = git.exec(hostOf(repo.id).toFile(), "git", "rev-parse", repo.mainBranch).trim();
    String parentSha =
        git.exec(hostOf(repo.id).toFile(), "git", "rev-parse", repo.mainBranch + "~1").trim();
    git.exec(
        hostOf(repo.id).toFile(), "git", "update-ref", "refs/heads/" + repo.mainBranch, parentSha);

    repositoryService.pullRepository(repo.id); // must not throw

    assertEquals(
        fullSha,
        git.exec(hostOf(repo.id).toFile(), "git", "rev-parse", repo.mainBranch).trim(),
        "the token-carrying pull fast-forwarded the protected branch on the host");
  }

  @Test
  public void deleteBranchNeverCarriesTheTokenEvenWhenOneIsConfigured() throws Exception {
    var project = projectService.create("Push Token Delete", null);
    var repo = repositoryService.cloneRepository(GitFixtures.path("testing-repo.git"), null, project);
    fakeGitHostRepositories.protectDefaultBranch(repo.id, TOKEN);

    // A token IS configured (this whole class's profile) and it WOULD be accepted by the fake
    // host's hook — so a refusal here can only mean deleteBranch's push never attached it.
    BadRequestException refused =
        assertThrows(
            BadRequestException.class,
            () -> repositoryService.deleteBranch(repo.id, repo.mainBranch));
    assertTrue(
        refused.getMessage().contains("declined") || refused.getMessage().contains("rejected"),
        "the host's own refusal reaches the caller: " + refused.getMessage());
  }
}
