package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.BadRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wrapper's {@code .gitmodules} as text a person still has to read.
 *
 * <p>A project <em>is</em> its wrapper repository and this file <em>is</em> the project's
 * configuration, so it is edited the way a person would edit it: one section appended or one section
 * cut, every other byte left exactly where it was. Nothing here rewrites, reorders or reformats a
 * section it was not asked about — a round trip through a config library would silently reflow the
 * whole file and turn every wrapper commit into an unreviewable diff.
 *
 * <p>The entry shape is fixed and is the whole point of the feature:
 *
 * <pre>
 * [submodule "&lt;name&gt;"]
 *         path = &lt;directory&gt;/&lt;name&gt;
 *         url = ../&lt;name&gt;.git
 *         branch = main
 *         ignore = all
 *         update = merge
 * </pre>
 *
 * <p>The <b>relative</b> url is what makes one wrapper resolve its siblings both at the forge and on
 * the platform git host's name-addressed route, with nothing to rewrite in between. The directory is
 * the archetype ({@link eu.wohlben.qits.projects.entity.RepositoryArchetype#directory()}), so moving
 * an entry between directories is how a component changes kind.
 *
 * <p>Pure and static: no CDI, no git, no IO — which is what lets its whole contract be tested
 * against strings.
 */
public final class WrapperGitmodules {

  private WrapperGitmodules() {}

  /** The keys this writer emits, in the order it emits them. */
  private static final String INDENT = "\t";

  /** One parsed section. {@code path} and {@code url} may be null for a malformed one. */
  public record Entry(String name, String path, String url) {}

  /**
   * Every {@code [submodule "…"]} section, in file order. A section with no {@code path} still
   * appears — the caller decides what an incomplete entry means, and dropping it here would make the
   * name look free to {@link #addEntry}.
   */
  public static List<Entry> entries(String content) {
    List<Entry> out = new ArrayList<>();
    for (Section section : sections(content)) {
      out.add(new Entry(section.name, section.keys.get("path"), section.keys.get("url")));
    }
    return out;
  }

  /**
   * {@code content} with a submodule entry for {@code name} mounted under {@code directory}.
   *
   * <p>Idempotent: a section already declaring exactly this name, path and url is left alone and the
   * content comes back unchanged, so a retried wrapper commit is a no-op rather than a duplicate.
   *
   * @throws BadRequestException when the name is already used for a different path or url, or when
   *     another entry already occupies the path — either is a real collision, and guessing which
   *     entry the author meant is not this method's decision to make
   */
  public static String addEntry(String content, String name, String directory) {
    requireName(name);
    if (directory == null || directory.isBlank()) {
      throw new BadRequestException("A wrapper entry needs a directory to be mounted under");
    }
    String path = directory + "/" + name;
    String url = "../" + name + ".git";

    for (Section section : sections(content)) {
      boolean sameName = name.equals(section.name);
      boolean samePath = path.equals(section.keys.get("path"));
      if (sameName && samePath && url.equals(section.keys.get("url"))) {
        return content; // already there, byte for byte
      }
      if (sameName) {
        throw new BadRequestException(
            "The wrapper already has a submodule named '"
                + name
                + "' at '"
                + section.keys.get("path")
                + "'; move or remove that entry before adding it at '"
                + path
                + "'.");
      }
      if (samePath) {
        throw new BadRequestException(
            "The wrapper already mounts a submodule at '"
                + path
                + "' (section '"
                + section.name
                + "'); two submodules cannot share a path.");
      }
    }

    StringBuilder out = new StringBuilder(content == null ? "" : content);
    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
      out.append('\n');
    }
    out.append("[submodule \"").append(name).append("\"]\n");
    out.append(INDENT).append("path = ").append(path).append('\n');
    out.append(INDENT).append("url = ").append(url).append('\n');
    out.append(INDENT).append("branch = main\n");
    out.append(INDENT).append("ignore = all\n");
    out.append(INDENT).append("update = merge\n");
    return out.toString();
  }

  /**
   * {@code content} without the section named {@code name}. Idempotent: no such section and the
   * content comes back unchanged. Every other section keeps its bytes.
   */
  public static String removeEntry(String content, String name) {
    requireName(name);
    if (content == null || content.isEmpty()) {
      return content == null ? "" : content;
    }
    String[] lines = content.split("\n", -1);
    List<Section> sections = sections(content);
    boolean[] drop = new boolean[lines.length];
    boolean found = false;
    for (Section section : sections) {
      if (!name.equals(section.name)) {
        continue;
      }
      found = true;
      for (int i = section.start; i < section.end; i++) {
        drop[i] = true;
      }
    }
    if (!found) {
      return content;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (drop[i]) {
        continue;
      }
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append(lines[i]);
    }
    // Cutting the LAST section takes the empty tail element the trailing newline produced with it,
    // which would silently drop the file's final newline. Put it back.
    if (out.length() > 0 && content.endsWith("\n") && out.charAt(out.length() - 1) != '\n') {
      out.append('\n');
    }
    return out.toString();
  }

  /** Whether {@code content} declares a submodule named {@code name}. */
  public static boolean hasEntry(String content, String name) {
    return entries(content).stream().anyMatch(entry -> name.equals(entry.name()));
  }

  // -------------------------------------------------------------------------------------------
  // parsing — line spans, so an edit can splice rather than re-render
  // -------------------------------------------------------------------------------------------

  /** A section's half-open line span, its name and its keys. */
  private static final class Section {
    final int start;
    int end;
    final String name;
    final Map<String, String> keys = new LinkedHashMap<>();

    Section(int start, String name) {
      this.start = start;
      this.name = name;
    }
  }

  private static List<Section> sections(String content) {
    List<Section> out = new ArrayList<>();
    if (content == null || content.isEmpty()) {
      return out;
    }
    String[] lines = content.split("\n", -1);
    Section current = null;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.startsWith("[")) {
        if (current != null) {
          current.end = i;
        }
        String name = sectionName(line);
        current = name == null ? null : new Section(i, name);
        if (current != null) {
          out.add(current);
        }
        continue;
      }
      if (current == null || line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
        continue;
      }
      int eq = line.indexOf('=');
      if (eq < 0) {
        continue;
      }
      // Last value wins, as git config itself reads a repeated key.
      current.keys.put(
          line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT),
          line.substring(eq + 1).trim());
    }
    // Only the last section can still be open — every earlier one was closed by the next header.
    for (Section section : out) {
      if (section.end == 0) {
        section.end = lines.length;
      }
    }
    return out;
  }

  /**
   * {@code src/main/webui} out of {@code [submodule "src/main/webui"]}, or null for a section that
   * is not a submodule (a {@code [core]} block someone added by hand stays untouched).
   */
  private static String sectionName(String header) {
    String inner = header.substring(1, header.endsWith("]") ? header.length() - 1 : header.length());
    if (!inner.trim().toLowerCase(java.util.Locale.ROOT).startsWith("submodule")) {
      return null;
    }
    int firstQuote = inner.indexOf('"');
    int lastQuote = inner.lastIndexOf('"');
    if (firstQuote >= 0 && lastQuote > firstQuote) {
      return inner.substring(firstQuote + 1, lastQuote);
    }
    return null;
  }

  private static void requireName(String name) {
    if (name == null || name.isBlank() || name.contains("\"") || name.contains("\n")) {
      throw new BadRequestException("Invalid submodule name: '" + name + "'");
    }
  }
}
