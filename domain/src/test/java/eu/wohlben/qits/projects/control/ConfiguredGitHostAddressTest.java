package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The exact absolute urls {@link ConfiguredGitHostAddress} builds — the contract every wire call in
 * {@code gitmirror} and {@code HttpGitHostRepositories} is pointed at. {@code DnsDomainRegistrarTest}'s
 * discipline: assert the address itself, not the constant, because a path that stops matching
 * qits-platform-artifacts' {@code /artifacts/git/<repoId>} raises nothing anywhere — every mirror
 * clone, fetch and lifecycle call would simply 404.
 */
class ConfiguredGitHostAddressTest {

  private ConfiguredGitHostAddress address(String artifactsUrl) {
    ConfiguredGitHostAddress address = new ConfiguredGitHostAddress();
    address.artifactsUrl = artifactsUrl;
    return address;
  }

  @Test
  void fetchUrlIsTheArtifactsGitSegment() {
    assertEquals(
        "http://qits-platform-artifacts:8080/artifacts/git/repo-1",
        address("http://qits-platform-artifacts:8080").fetchUrl("repo-1"));
  }

  @Test
  void aTrailingSlashOnTheConfiguredBaseIsStripped() {
    assertEquals(
        "http://qits-platform-artifacts:8080/artifacts/git/repo-1",
        address("http://qits-platform-artifacts:8080/").fetchUrl("repo-1"));
  }

  @Test
  void pushUrlIsTheSameAddressAsFetchUrl() {
    ConfiguredGitHostAddress address = address("http://qits-platform-artifacts:8080");

    assertEquals(address.fetchUrl("repo-1"), address.pushUrl("repo-1"), "one address, not two");
  }

  @Test
  void aDifferentHostAndSchemeAreHonoured() {
    assertEquals(
        "https://artifacts.example.internal/artifacts/git/qits-qits",
        address("https://artifacts.example.internal").fetchUrl("qits-qits"));
  }
}
