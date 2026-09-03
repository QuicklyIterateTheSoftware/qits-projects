package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.ManifestBumpException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The bump engine's one door: given the <b>tree of a commit</b> and a version, produce the rewritten
 * bytes of whatever manifests that tree renders versions through.
 *
 * <h2>There is no checkout, and that is the whole difference from qits-workspaces</h2>
 *
 * That service ran the same engine inside a detached worktree: it walked directories, read files and
 * wrote them back. This service holds no worktree and no mirror of the repository it is releasing —
 * the fold was made by qits-githost's merge primitive and lives only there — so the engine reads
 * through {@link Source} (qits-githost's tree listing and blob reads at one rev) and <b>returns</b>
 * the new bytes instead of writing them. The caller commits them in one call. Nothing here touches a
 * filesystem, opens a socket or reads configuration, which is what lets its tests be exhaustive with
 * no service in the way.
 *
 * <h2>Detection is the ROOT of the tree and nothing else</h2>
 *
 * {@code pom.xml} at the root means MAVEN, {@code package.json} at the root means NPM, both means
 * both with the same version string, neither means neither — and neither is still a release. A
 * nested pom the root reactor does not list as a {@code <module>} is not part of the build and a
 * nested {@code package.json} is almost always inside a {@code dist/}; walking the listing for
 * manifests would find those.
 *
 * <h2>What moves</h2>
 *
 * <pre>
 *   maven   every reactor pom's own &lt;version&gt; and its in-reactor &lt;parent&gt;&lt;version&gt;
 *           — one element per pom, six for a five-module reactor — plus any LITERAL in-reactor
 *           dependency version. ${…} expressions are left exactly alone: they already follow.
 *           The reactor is walked by &lt;module&gt;, never by scanning the listing, which is the
 *           only definition of "the reactor" that agrees with what maven itself would build.
 *
 *   npm     package.json .version, package-lock.json .version and .packages[""].version — the
 *           three fields `npm ci` compares, and the whole edit surface — plus each
 *           projects/&lt;lib&gt;/package.json, which for a publishable Angular library repository
 *           is the manifest that actually gets released.
 * </pre>
 *
 * <p><b>Never a lockfile regeneration.</b> Every SPA's committed lock pins {@code resolved} URLs
 * against this platform's own registry and the pipelines rewrite roughly 700 of them; regenerating
 * one would commit a lock that no longer resolves on a developer's host. Three spliced spans, and
 * nothing else in the file moves.
 *
 * <p><b>Absent is loud.</b> A detected stack whose manifest cannot be read, cannot be parsed, or
 * declares no version is a {@link ManifestBumpException} and therefore a failed release — never a
 * skip. A bump that silently skipped a file would ship a release whose artifacts still carry the
 * previous version, and that is discovered much later and much further away.
 */
public final class ManifestVersionBump {

  /**
   * What a version string may contain for both splices to be safe without escaping. {@link
   * VersionStamp} produces digits and dots; the guard exists so that a caller passing something else
   * fails here rather than writing an unparseable pom or an invalid JSON string.
   */
  private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

  private static final String ROOT_POM = "pom.xml";
  private static final String ROOT_PACKAGE_JSON = "package.json";
  private static final String ROOT_LOCK = "package-lock.json";

  private static final String JSON_VERSION = "/version";
  private static final String JSON_LOCK_ROOT_PACKAGE_VERSION = "/packages//version";
  private static final String JSON_LOCK_ROOT_PACKAGE = "/packages/";

  /** {@code projects/<anything but a slash>/package.json} — the Angular library convention. */
  private static final Pattern LIBRARY_MANIFEST =
      Pattern.compile("projects/[^/]+/package\\.json");

  private ManifestVersionBump() {}

  /** A build stack whose manifests render the release version. */
  public enum Stack {
    MAVEN,
    NPM
  }

  /**
   * One commit's tree, as the bump engine needs to see it. The implementation in this service is
   * qits-githost's {@code /tree} and {@code /file} reads at one rev; the suite's is a map.
   */
  public interface Source {

    /**
     * Every blob path in the tree, slash-separated and repository-relative, in any order. Gitlinks
     * are absent — a submodule has no blob to bump.
     */
    List<String> paths();

    /**
     * The UTF-8 text of one path, which {@link #paths()} listed.
     *
     * @throws ManifestBumpException if it cannot be read, which is a failed release rather than an
     *     empty manifest
     */
    String read(String path);
  }

  /**
   * What the bump produced.
   *
   * @param version the version written into every file below, unchanged
   * @param stacks the stacks detected at the root; empty is a supported answer
   * @param files path → the file's new bytes, for every file whose bytes actually changed. Empty
   *     when the repository renders no version, and empty is <b>not</b> a failure: a stackless
   *     repository still gets a tag, which is the release.
   */
  public record Result(String version, Set<Stack> stacks, Map<String, String> files) {}

  /** Stamp {@code version} into every manifest {@code source} renders a version through. */
  public static Result stamp(Source source, String version) {
    if (version == null || !SAFE_VERSION.matcher(version).matches()) {
      throw new ManifestBumpException("refusing to stamp an unusable version string: " + version);
    }
    Set<String> paths = new LinkedHashSet<>(source.paths());
    Set<Stack> stacks = EnumSet.noneOf(Stack.class);
    if (paths.contains(ROOT_POM)) {
      stacks.add(Stack.MAVEN);
    }
    if (paths.contains(ROOT_PACKAGE_JSON)) {
      stacks.add(Stack.NPM);
    }

    Map<String, String> files = new LinkedHashMap<>();
    // Both stacks in one repository is unreachable in this platform today and handled anyway, with
    // the same version string in both — one `if` each, so the behaviour is defined rather than
    // emergent the first time a repository grows a second stack.
    if (stacks.contains(Stack.MAVEN)) {
      bumpReactor(source, paths, version, files);
    }
    if (stacks.contains(Stack.NPM)) {
      bumpNpm(source, paths, version, files);
    }
    return new Result(version, Set.copyOf(stacks), Map.copyOf(files));
  }

  // ---------------------------------------------------------------------------------------------
  // Maven
  // ---------------------------------------------------------------------------------------------

  private static void bumpReactor(
      Source source, Set<String> paths, String version, Map<String, String> files) {
    Map<String, String> texts = new LinkedHashMap<>();
    Map<String, PomVersions.Scan> reactor = collect(source, paths, texts);

    Set<String> reactorArtifacts = new HashSet<>();
    Set<String> reactorCoordinates = new HashSet<>();
    for (PomVersions.Scan scan : reactor.values()) {
      reactorArtifacts.add(scan.artifactId());
      reactorCoordinates.add(scan.groupId() + ":" + scan.artifactId());
    }

    int elements = 0;
    for (Map.Entry<String, PomVersions.Scan> entry : reactor.entrySet()) {
      String pom = entry.getKey();
      PomVersions.Scan scan = entry.getValue();
      List<TextSplice.Span> spans = new ArrayList<>();

      if (scan.version() != null) {
        spans.add(scan.version().span());
      }
      if (scan.parentVersion() != null
          && reactorCoordinates.contains(scan.parentGroupId() + ":" + scan.parentArtifactId())) {
        spans.add(scan.parentVersion().span());
      }
      for (PomVersions.Dependency dependency : scan.dependencies()) {
        if (dependency.version() == null) {
          continue;
        }
        if (dependency.version().value().trim().contains("${")) {
          continue;
        }
        if (namesAReactorModule(dependency, scan, reactorArtifacts)) {
          spans.add(dependency.version().span());
        }
      }

      if (spans.isEmpty()) {
        continue;
      }
      String original = texts.get(pom);
      String bumped = TextSplice.replaceAll(original, spans, version);
      elements += spans.size();
      if (!bumped.equals(original)) {
        files.put(pom, bumped);
      }
    }

    if (elements == 0) {
      throw new ManifestBumpException(
          "no version element found in the maven reactor rooted at pom.xml; refusing to report a"
              + " release whose poms still carry the previous version");
    }
  }

  /**
   * A dependency belongs to this reactor when its artifactId names a module of it and its groupId
   * either matches, is absent, or is an expression — {@code ${project.groupId}} is how every
   * inter-module dependency in this platform spells it, and resolving properties is not this
   * engine's job.
   */
  private static boolean namesAReactorModule(
      PomVersions.Dependency dependency, PomVersions.Scan owner, Set<String> reactorArtifacts) {
    if (!reactorArtifacts.contains(dependency.artifactId())) {
      return false;
    }
    String groupId = dependency.groupId();
    return groupId == null
        || groupId.contains("${")
        || groupId.equals(owner.groupId())
        || groupId.equals(owner.parentGroupId());
  }

  /** Every pom of the reactor, in breadth-first {@code <module>} order, root first. */
  private static Map<String, PomVersions.Scan> collect(
      Source source, Set<String> paths, Map<String, String> texts) {
    Map<String, PomVersions.Scan> reactor = new LinkedHashMap<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(ROOT_POM);
    while (!queue.isEmpty()) {
      String pom = queue.removeFirst();
      if (reactor.containsKey(pom)) {
        continue;
      }
      String text = source.read(pom);
      PomVersions.Scan scan = PomVersions.scan(text, pom);
      texts.put(pom, text);
      reactor.put(pom, scan);
      for (String module : scan.modules()) {
        queue.add(resolveModule(paths, pom, module));
      }
    }
    return reactor;
  }

  /**
   * A {@code <module>} as a path in the tree. Maven resolves it against the declaring pom's own
   * directory and accepts either the directory or the pom itself; a module that escapes the
   * repository, or that names nothing in the tree, is a loud failure rather than a skipped pom.
   */
  private static String resolveModule(Set<String> paths, String pom, String module) {
    if (module.isBlank()) {
      throw new ManifestBumpException("blank <module> in " + pom);
    }
    String directory = parentOf(pom);
    String candidate = normalize(directory.isEmpty() ? module : directory + "/" + module);
    if (candidate == null) {
      throw new ManifestBumpException(
          "<module>" + module + "</module> in " + pom + " escapes the repository");
    }
    if (paths.contains(candidate) && candidate.endsWith(".xml")) {
      return candidate;
    }
    String modulePom = candidate.isEmpty() ? ROOT_POM : candidate + "/pom.xml";
    if (!paths.contains(modulePom)) {
      throw new ManifestBumpException(
          "<module>" + module + "</module> in " + pom + " has no pom.xml (looked for " + modulePom
              + ")");
    }
    return modulePom;
  }

  /** The directory a path lives in, {@code ""} for a root-level file. */
  private static String parentOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  /**
   * A slash path with {@code .} and {@code ..} folded out, or null when it climbs out of the tree.
   * Hand-rolled rather than {@code java.nio.file.Path}: there is no filesystem here, and a
   * platform-dependent separator has no business in a git path.
   */
  private static String normalize(String path) {
    List<String> segments = new ArrayList<>();
    for (String segment : path.split("/")) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        if (segments.isEmpty()) {
          return null;
        }
        segments.remove(segments.size() - 1);
        continue;
      }
      segments.add(segment);
    }
    return String.join("/", segments);
  }

  // ---------------------------------------------------------------------------------------------
  // npm
  // ---------------------------------------------------------------------------------------------

  private static void bumpNpm(
      Source source, Set<String> paths, String version, Map<String, String> files) {
    bumpManifest(source, ROOT_PACKAGE_JSON, version, files);
    if (paths.contains(ROOT_LOCK)) {
      bumpLock(source, version, files);
    }
    // Sorted, so the commit's file map is the same for the same tree whatever order the git host
    // listed it in.
    paths.stream()
        .filter(path -> LIBRARY_MANIFEST.matcher(path).matches())
        .sorted()
        .forEach(manifest -> bumpManifest(source, manifest, version, files));
  }

  private static void bumpManifest(
      Source source, String manifest, String version, Map<String, String> files) {
    String json = source.read(manifest);
    TextSplice.Span span =
        PackageJsonVersions.locate(json, Set.of(JSON_VERSION), manifest).get(JSON_VERSION);
    if (span == null) {
      throw new ManifestBumpException(manifest + " declares no \"version\"");
    }
    put(files, manifest, json, List.of(span), version);
  }

  private static void bumpLock(Source source, String version, Map<String, String> files) {
    String json = source.read(ROOT_LOCK);
    Map<String, TextSplice.Span> spans =
        PackageJsonVersions.locate(
            json, Set.of(JSON_VERSION, JSON_LOCK_ROOT_PACKAGE_VERSION), ROOT_LOCK);

    TextSplice.Span root = spans.get(JSON_VERSION);
    if (root == null) {
      throw new ManifestBumpException(ROOT_LOCK + " declares no \"version\"");
    }
    TextSplice.Span rootPackage = spans.get(JSON_LOCK_ROOT_PACKAGE_VERSION);
    if (rootPackage == null
        && !PackageJsonVersions.fieldsPresent(json, Set.of(JSON_LOCK_ROOT_PACKAGE), ROOT_LOCK)
            .isEmpty()) {
      throw new ManifestBumpException(
          ROOT_LOCK
              + " has a \"packages\" entry for the root package but no version in it; npm ci"
              + " compares that field against package.json and fails with EUSAGE when they"
              + " disagree");
    }
    List<TextSplice.Span> all = new ArrayList<>();
    all.add(root);
    if (rootPackage != null) {
      all.add(rootPackage);
    }
    put(files, ROOT_LOCK, json, all, version);
  }

  private static void put(
      Map<String, String> files,
      String path,
      String json,
      List<TextSplice.Span> spans,
      String version) {
    String bumped = TextSplice.replaceAll(json, spans, '"' + version + '"');
    if (!bumped.equals(json)) {
      files.put(path, bumped);
    }
  }
}
