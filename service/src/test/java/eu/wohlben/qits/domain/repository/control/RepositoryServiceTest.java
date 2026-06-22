package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.project.control.ProjectService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(RepositoryServiceTest.TestProfile.class)
public class RepositoryServiceTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject RepositoryService repositoryService;

  @Inject ProjectService projectService;

  @Test
  public void testClone() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Clone Project", null);
    System.out.println("FIXTURE URL: " + fixtureUrl);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    System.out.println("CLONED: " + repo.id);
  }
}
