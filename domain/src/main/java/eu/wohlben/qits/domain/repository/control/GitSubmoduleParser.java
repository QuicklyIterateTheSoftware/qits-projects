package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Parses a superproject's {@code .gitmodules} and resolves relative submodule urls the way git
 * does. Kept as a pure, framework-light helper (separate from the transactional {@link
 * RepositoryService}) so parsing and url resolution are unit-testable without cloning anything.
 *
 * <p>The whole feature is gated on "has {@code .gitmodules}": {@link #readSubmodules} returns an
 * empty list for a repository without one, so a submodule-free import takes the identical old path.
 */
@ApplicationScoped
public class GitSubmoduleParser {

  private static final Logger LOG = Logger.getLogger(GitSubmoduleParser.class);

  @Inject GitExecutor git;

  /** One {@code .gitmodules} entry. {@code url} is the raw (possibly relative) value. */
  public record Submodule(String name, String path, String url, Optional<String> branch) {}

  /**
   * The submodules declared in {@code branch}'s {@code .gitmodules} in the bare origin. A missing
   * {@code .gitmodules} (non-zero {@code git show}) yields an empty list — the no-op branch that
   * keeps submodule-free repositories on the old path.
   */
  public List<Submodule> readSubmodules(File bareOrigin, String branch) {
    try {
      GitExecutor.ExecResult result = git.showFile(bareOrigin, branch, ".gitmodules");
      if (result.exitCode() != 0) {
        return List.of();
      }
      return parse(result.output());
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Failed to read .gitmodules from %s@%s; treating as no submodules",
          bareOrigin,
          branch);
      return List.of();
    }
  }

  /**
   * Parses the INI-style {@code .gitmodules} content. Entries missing {@code path}/{@code url}, or
   * with an unsafe url (argv flag / {@code ext::} transport), are dropped rather than failing the
   * whole import — a malformed or hostile submodule simply is not imported.
   */
  public List<Submodule> parse(String content) {
    List<Submodule> submodules = new ArrayList<>();
    String name = null;
    String path = null;
    String url = null;
    String branch = null;
    for (String rawLine : content.split("\n")) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
        continue;
      }
      if (line.startsWith("[")) {
        // New section — flush the previous one before starting.
        addIfValid(submodules, name, path, url, branch);
        name = sectionName(line);
        path = null;
        url = null;
        branch = null;
        continue;
      }
      int eq = line.indexOf('=');
      if (eq < 0 || name == null) {
        continue;
      }
      String key = line.substring(0, eq).trim().toLowerCase();
      String value = line.substring(eq + 1).trim();
      switch (key) {
        case "path" -> path = value;
        case "url" -> url = value;
        case "branch" -> branch = value;
        default -> {
          // ignore other keys (update, fetchRecurseSubmodules, …)
        }
      }
    }
    addIfValid(submodules, name, path, url, branch);
    return submodules;
  }

  private void addIfValid(
      List<Submodule> out, String name, String path, String url, String branch) {
    if (name == null || path == null || path.isBlank() || url == null || url.isBlank()) {
      return;
    }
    if (!isUrlSafe(url)) {
      LOG.warnf("Skipping submodule %s: unsafe url %s", name, url);
      return;
    }
    out.add(new Submodule(name, path, url, Optional.ofNullable(branch).filter(b -> !b.isBlank())));
  }

  /**
   * Extracts {@code src/main/webui} from a section header like {@code [submodule
   * "src/main/webui"]}.
   */
  private String sectionName(String header) {
    int firstQuote = header.indexOf('"');
    int lastQuote = header.lastIndexOf('"');
    if (firstQuote >= 0 && lastQuote > firstQuote) {
      return header.substring(firstQuote + 1, lastQuote);
    }
    // Fall back to the token between "[" and "]" (no quoted name) — best effort.
    return header.replaceAll("[\\[\\]]", "").replace("submodule", "").trim();
  }

  /**
   * Resolves a submodule's url the way git's {@code resolve_relative_url} does. A url that does not
   * start with {@code ./} or {@code ../} is absolute and returned unchanged. Otherwise each leading
   * {@code ../} strips the last path component of the superproject url (never crossing into the
   * scheme/authority or scp {@code host:} head), each {@code ./} strips nothing, and the remainder
   * is appended — so {@code ../foo.git} resolves against {@code https://h/o/super.git} to {@code
   * https://h/o/foo.git} and against a local {@code /abs/super.git} to {@code /abs/foo.git}.
   */
  public String resolveSubmoduleUrl(String superprojectUrl, String rawUrl) {
    String rel = rawUrl.trim();
    if (!rel.startsWith("./") && !rel.startsWith("../")) {
      return rel;
    }
    String base = superprojectUrl.trim();
    // Split off the scheme+authority (scheme://authority) or scp-style host: so "../" folding stays
    // within the path portion and never eats the host.
    String head;
    String path;
    int schemeIdx = base.indexOf("://");
    if (schemeIdx >= 0) {
      int authStart = schemeIdx + 3;
      int slash = base.indexOf('/', authStart);
      if (slash < 0) {
        head = base;
        path = "";
      } else {
        head = base.substring(0, slash);
        path = base.substring(slash);
      }
    } else {
      int colon = base.indexOf(':');
      int firstSlash = base.indexOf('/');
      if (colon > 0
          && (firstSlash < 0 || colon < firstSlash)
          && base.substring(0, colon).indexOf('/') < 0) {
        // scp-like: user@host:path
        head = base.substring(0, colon + 1);
        path = base.substring(colon + 1);
      } else {
        head = "";
        path = base;
      }
    }
    // Strip a trailing slash so the last component is the repo name.
    while (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    String r = rel;
    while (r.startsWith("../") || r.startsWith("./")) {
      if (r.startsWith("../")) {
        int idx = path.lastIndexOf('/');
        path = idx >= 0 ? path.substring(0, idx) : "";
        r = r.substring(3);
      } else {
        r = r.substring(2);
      }
    }
    return head + path + "/" + r;
  }

  /**
   * The same argv-safety gate {@link RepositoryService} applies to import urls: reject a
   * dash-leading value (argv flag injection) and the {@code ext::} transport (arbitrary command
   * execution).
   */
  private boolean isUrlSafe(String url) {
    String u = url.trim();
    return !u.isBlank() && !u.startsWith("-") && !u.regionMatches(true, 0, "ext::", 0, 5);
  }
}
