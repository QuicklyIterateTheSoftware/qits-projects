package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.ReleaseArtifactDto;
import eu.wohlben.qits.projects.dto.ReleaseArtifactsDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * <b>What a release published, read out of the released tag's own tree.</b> The one answer this
 * service can give to "the release landed — now where is the thing it made".
 *
 * <p><b>The tree is the source, and that is a decision rather than a convenience.</b> qits-ci
 * announces a {@code SoftwareRelease} per published artifact, so a record of what was built exists —
 * somewhere else, for the releases whose repositories carry a recipe, and never for the ones that do
 * not. The recipe at the tag is a fact this service can always reach and that cannot go stale: it is
 * the declaration the pipeline itself acted on. So a release made before this endpoint existed is
 * answerable, and one whose CI announced nothing at all is answerable too.
 *
 * <p><b>Nothing here is an error.</b> The port cannot be asked, the tag cannot be read, the recipe
 * will not parse — each is an answer about the release with a sentence on {@code detail}, exactly
 * the stance {@link ReleaseFinalization#deployability} takes over the same tree read, and for the
 * same reason: a panel that says "we could not ask" is useful and a 500 is not. The only refusal is
 * a request id that names nothing.
 *
 * <p><b>Absent is not empty-with-an-excuse.</b> A repository with no {@link #RELEASE_RECIPE} at all
 * publishes nothing and gets no {@code detail} — every SPA on this platform is in that case, and
 * putting a sentence there would turn the ordinary answer into a warning.
 */
@ApplicationScoped
public class ReleaseArtifacts {

  private static final Logger LOG = Logger.getLogger(ReleaseArtifacts.class);

  /** The release pipeline's own declaration of what it publishes — {@code artifacts:} is read. */
  static final String RELEASE_RECIPE = ".config/qits/ci-event-release.yml";

  /**
   * The per-release-request QA pipeline. It is read for one substring and never parsed: what is
   * wanted from it is whether this repository publishes a userflow bundle, and that is a shell line
   * inside a step rather than anything the recipe declares.
   */
  static final String QA_RECIPE = ".config/qits/ci-event-release-request.yml";

  /** The docs scope every userflow bundle on this platform is published under. */
  static final String USERFLOWS_SCOPE = "@userflows/";

  /** {@code @userflows/<site>} as it appears in the QA recipe's publish line. */
  private static final Pattern USERFLOWS_SITE =
      Pattern.compile(Pattern.quote(USERFLOWS_SCOPE) + "([A-Za-z0-9][A-Za-z0-9._-]*)");

  @Inject ReleaseRequestRepository requests;

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  @Inject Instance<ReleaseGitHost> gitHosts;

  /** What one release request's tag carries, or why that cannot be said. */
  public ReleaseArtifactsDto of(String repoId, String requestId) {
    Released released =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row =
                      requests
                          .findByIdOptional(requestId)
                          .filter(candidate -> candidate.repoId.equals(repoId))
                          .orElseThrow(
                              () ->
                                  new NotFoundException(
                                      "Release request not found: " + requestId));
                  if (row.state != ReleaseRequest.State.RELEASED
                      || row.version == null
                      || row.version.isBlank()) {
                    return null;
                  }
                  return new Released(
                      row.version.trim(),
                      pendingTags
                          .findByRequest(requestId)
                          .map(tag -> tag.releasedSha)
                          .orElse(null),
                      row.repoName,
                      row.mergedSha);
                });
    if (released == null) {
      // The honest answer to "what did this publish" for a request that has not released, and not
      // an error: the page asks it of every request it draws.
      return new ReleaseArtifactsDto(null, null, false, List.of(), "Not released yet");
    }
    return read(repoId, released);
  }

  /** The facts about one landed release that the tree read needs, carried out of its transaction. */
  private record Released(String version, String releasedSha, String repoName, String mergedSha) {}

  private ReleaseArtifactsDto read(String repoId, Released released) {
    if (!gitHosts.isResolvable()) {
      return nothing(
          released,
          false,
          "No git host is configured, so what this release published cannot be read");
    }
    ReleaseGitHost host = gitHosts.get();
    String rev = "refs/tags/" + released.version();
    ReleaseGitHost.Answer<List<String>> tree;
    try {
      tree = host.tree(repoId, rev);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not be read as an answer.
      LOG.debugf(e, "The git host threw reading the tree of %s at %s", repoId, rev);
      return nothing(released, false, "The git host could not be asked what " + rev + " contains");
    }
    if (!tree.ok()) {
      return nothing(released, false, tree.detail());
    }
    // The same file, the same reading, one seam over: this is the platform's declaration that
    // something deploys the repository, and ReleaseFinalization forks the whole publish phase on it.
    boolean deployable = tree.value().contains(ReleaseFinalization.DEPLOYMENTS_MANIFEST);

    List<ReleaseArtifactDto> artifacts = new ArrayList<>();
    if (tree.value().contains(RELEASE_RECIPE)) {
      ReleaseGitHost.Answer<String> recipe = file(host, repoId, rev, RELEASE_RECIPE);
      if (recipe == null || !recipe.ok()) {
        return nothing(
            released,
            deployable,
            "The release recipe could not be read: "
                + (recipe == null ? "the git host could not be asked" : recipe.detail()));
      }
      try {
        artifacts.addAll(declared(recipe.value(), released.version()));
      } catch (RuntimeException e) {
        LOG.debugf("The release recipe of %s at %s does not parse: %s", repoId, rev, e.toString());
        return nothing(
            released, deployable, "The release recipe does not declare a readable artifact list");
      }
    }
    userflows(host, repoId, rev, tree.value(), released).ifPresent(artifacts::add);
    return new ReleaseArtifactsDto(
        released.version(),
        released.releasedSha(),
        deployable,
        List.copyOf(artifacts),
        // A repository that declares nothing published nothing, and that is not a problem to
        // explain. The empty list is the whole answer.
        null);
  }

  /**
   * The bundle the QA pipeline publishes, where it publishes one.
   *
   * <p><b>Derived, because nothing declares it.</b> The userflow docs site is a {@code curl} inside
   * a step rather than an entry under {@code artifacts:}, so the recipe is read for the coordinate
   * it spells rather than parsed. The site name is taken from the recipe itself and not composed
   * from the repository's name, because the two genuinely differ — {@code qits-projects-service}
   * publishes {@code @userflows/qits-projects} — and a composed name would be a link to nothing.
   *
   * <p><b>Its version is the fold's sha and not the calver</b>, because that pipeline runs per
   * release request and publishes at {@code $QITS_CI_SHA}. Asking for the calver would 404 on a
   * bundle that is certainly there.
   */
  private Optional<ReleaseArtifactDto> userflows(
      ReleaseGitHost host, String repoId, String rev, List<String> tree, Released released) {
    if (!tree.contains(QA_RECIPE) || released.mergedSha() == null) {
      return Optional.empty();
    }
    ReleaseGitHost.Answer<String> recipe = file(host, repoId, rev, QA_RECIPE);
    if (recipe == null || !recipe.ok()) {
      return Optional.empty();
    }
    Matcher site = USERFLOWS_SITE.matcher(recipe.value());
    String name =
        site.find()
            ? USERFLOWS_SCOPE + site.group(1)
            : (released.repoName() == null || released.repoName().isBlank()
                ? null
                : USERFLOWS_SCOPE + released.repoName());
    if (name == null || !recipe.value().contains(USERFLOWS_SCOPE)) {
      return Optional.empty();
    }
    return Optional.of(
        new ReleaseArtifactDto("userflows", name, released.mergedSha()));
  }

  /** {@link ReleaseGitHost#file} with the port's must-not-throw promise held to. */
  private ReleaseGitHost.Answer<String> file(
      ReleaseGitHost host, String repoId, String rev, String path) {
    try {
      return host.file(repoId, rev, path);
    } catch (RuntimeException e) {
      LOG.debugf(e, "The git host threw reading %s of %s at %s", path, repoId, rev);
      return null;
    }
  }

  /**
   * The {@code artifacts:} list of a release recipe, as {@link ReleaseArtifactDto}s at the released
   * version.
   *
   * <p>SnakeYAML's {@link SafeConstructor} — plain maps and lists, never an arbitrary class out of
   * repository content — the posture {@link QitsConfigParser} states and the reason it is worth
   * restating: this file comes from a repository and is not trusted input.
   *
   * <p>Anything that is not a list of mappings with a {@code type} and a {@code name} is thrown
   * rather than skipped, so the caller can say "the recipe does not parse" instead of quietly
   * answering a shorter list than the repository declares.
   */
  private static List<ReleaseArtifactDto> declared(String yaml, String version) {
    Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    if (root == null) {
      return List.of();
    }
    if (!(root instanceof Map<?, ?> document)) {
      throw new IllegalArgumentException("the document root is not a mapping");
    }
    Object declared = asMap(document).get("artifacts");
    if (declared == null) {
      return List.of();
    }
    if (!(declared instanceof List<?> entries)) {
      throw new IllegalArgumentException("artifacts is not a list");
    }
    List<ReleaseArtifactDto> out = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> item)) {
        throw new IllegalArgumentException("an artifacts entry is not a mapping");
      }
      Map<String, Object> fields = asMap(item);
      String type = text(fields.get("type"));
      String name = text(fields.get("name"));
      if (type == null || name == null) {
        throw new IllegalArgumentException("an artifacts entry names no type or no name");
      }
      out.add(new ReleaseArtifactDto(type, name, version));
    }
    return out;
  }

  private static Map<String, Object> asMap(Map<?, ?> raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    raw.forEach((key, value) -> out.put(String.valueOf(key), value));
    return out;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  /** A released request whose artifacts could not be established, with the reason on the answer. */
  private static ReleaseArtifactsDto nothing(
      Released released, boolean deployable, String detail) {
    return new ReleaseArtifactsDto(
        released.version(),
        released.releasedSha(),
        deployable,
        List.of(),
        detail == null ? "What this release published could not be read" : detail);
  }
}
