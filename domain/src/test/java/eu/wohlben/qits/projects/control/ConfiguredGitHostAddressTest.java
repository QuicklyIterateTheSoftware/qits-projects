package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The exact absolute urls {@link ConfiguredGitHostAddress} builds — the contract every wire call in
 * {@code gitmirror} and {@code HttpGitHostRepositories} is pointed at. The discipline is to assert
 * the address itself, not the constant, because a path that stops matching
 * qits-githost's {@code /git/<repoId>} raises nothing anywhere — every mirror
 * clone, fetch and lifecycle call would simply 404.
 */
class ConfiguredGitHostAddressTest {

  private ConfiguredGitHostAddress address(String gitHostUrl) {
    ConfiguredGitHostAddress address = new ConfiguredGitHostAddress();
    address.gitHostUrl = gitHostUrl;
    return address;
  }

  @Test
  void fetchUrlIsTheGitHostSegment() {
    assertEquals(
        "http://dev-qits-githost:8080/git/repo-1",
        address("http://dev-qits-githost:8080").fetchUrl("repo-1"));
  }

  @Test
  void aTrailingSlashOnTheConfiguredBaseIsStripped() {
    assertEquals(
        "http://dev-qits-githost:8080/git/repo-1",
        address("http://dev-qits-githost:8080/").fetchUrl("repo-1"));
  }

  @Test
  void pushUrlIsTheSameAddressAsFetchUrl() {
    ConfiguredGitHostAddress address = address("http://dev-qits-githost:8080");

    assertEquals(address.fetchUrl("repo-1"), address.pushUrl("repo-1"), "one address, not two");
  }

  @Test
  void aDifferentHostAndSchemeAreHonoured() {
    assertEquals(
        "https://githost.example.internal/git/qits-qits",
        address("https://githost.example.internal").fetchUrl("qits-qits"));
  }
}
