package eu.wohlben.qits.domain.repository.control;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

    @Inject
    RepositoryService repositoryService;

    @Test
    public void testClone() throws Exception {
        String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
        System.out.println("FIXTURE URL: " + fixtureUrl);
        var repo = repositoryService.cloneRepository("testrepo", fixtureUrl, null);
        System.out.println("CLONED: " + repo.id);
    }
}
