package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.InternalServerErrorException;
import io.quarkus.runtime.util.ClassPathUtils;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The <b>project template skeleton</b> every wrapper repository's first commit is made of: the
 * empty polyrepo layout the project will grow into, so a wrapper's {@code main} is never unborn and
 * the archetype taxonomy is visible on disk from the first clone.
 *
 * <p>It lives as ordinary committed files under {@code src/main/resources/project-template/} rather
 * than as strings in Java, so it stays reviewable, can grow without touching code, and can be
 * checked against {@link
 * eu.wohlben.qits.projects.entity.RepositoryArchetype#skeletonDirectories()} by a test.
 * Nothing in it is project-specific, so it is committed <b>verbatim</b> — no placeholders, no
 * template engine.
 */
@ApplicationScoped
public class ProjectTemplate {

  static final String RESOURCE_ROOT = "project-template";

  /**
   * Marks a resource whose <em>content</em> is a symlink target rather than file content. Maven
   * resource copying dereferences real symlinks and classpath loading cannot reproduce one, so the
   * template declares them instead: {@code CLAUDE.md.symlink} containing {@code AGENTS.md} is
   * committed as {@code CLAUDE.md} with git mode {@code 120000}.
   */
  static final String SYMLINK_SUFFIX = ".symlink";

  /**
   * Marks a path segment that is committed as a dotfile: {@code dot-gitignore} becomes {@code
   * .gitignore}.
   *
   * <p><b>Not cosmetic.</b> Plexus' archiver default-excludes — which maven-jar-plugin applies —
   * silently drop {@code **}{@code /.gitignore} and {@code **}{@code /.gitattributes} from every
   * jar. A template resource named {@code .gitignore} therefore lands in {@code target/classes} (so
   * tests and dev mode see it) and vanishes from the packaged artifact, producing wrappers that are
   * missing a file with no error anywhere. Prefixing sidesteps the exclusion entirely, and applies
   * to every dotfile so the next one added cannot reintroduce the trap.
   */
  static final String DOT_PREFIX = "dot-";

  /** Regular file. */
  static final String MODE_FILE = "100644";

  /** Symbolic link — a blob whose content is the link target. */
  static final String MODE_SYMLINK = "120000";

  /**
   * One entry of the skeleton.
   *
   * @param path the path it is committed at, relative to the repository root
   * @param mode the git file mode ({@link #MODE_FILE} or {@link #MODE_SYMLINK})
   * @param content the blob content — for a symlink, the link target
   */
  public record TemplateEntry(String path, String mode, byte[] content) {}

  /**
   * Read once per JVM: the template is immutable, on the classpath, and read on every project
   * creation — including ~100 times across a test suite run.
   */
  private volatile List<TemplateEntry> cached;

  /** The skeleton's entries, sorted by path so a seeded tree is deterministic. */
  public List<TemplateEntry> entries() {
    List<TemplateEntry> local = cached;
    if (local == null) {
      synchronized (this) {
        if (cached == null) {
          cached = read();
        }
        local = cached;
      }
    }
    return local;
  }

  private List<TemplateEntry> read() {
    List<TemplateEntry> entries = new ArrayList<>();
    try {
      // consumeAsPaths, not Path.of(getResource(...).toURI()): in a packaged fast-jar the template
      // lives inside a jar, whose `jar:` URI has no default FileSystem — the direct form works in
      // tests (exploded target/classes) and throws FileSystemNotFoundException in production.
      ClassPathUtils.consumeAsPaths(RESOURCE_ROOT, root -> entries.addAll(readFrom(root)));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the project template from the classpath", e);
    }
    if (entries.isEmpty()) {
      throw new InternalServerErrorException(
          "The project template is missing from the classpath (" + RESOURCE_ROOT + ")");
    }
    entries.sort(Comparator.comparing(TemplateEntry::path));
    return List.copyOf(entries);
  }

  private static List<TemplateEntry> readFrom(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .map(file -> toEntry(root, file))
          .collect(ArrayList::new, List::add, List::addAll);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to walk the project template at " + root, e);
    }
  }

  /** {@code dot-gitignore} → {@code .gitignore}; any other segment is returned unchanged. */
  private static String undotPrefix(String segment) {
    return segment.startsWith(DOT_PREFIX) ? "." + segment.substring(DOT_PREFIX.length()) : segment;
  }

  private static TemplateEntry toEntry(Path root, Path file) {
    // Always '/' — this becomes a git path, and the root may live in a jar FileSystem.
    String relative =
        java.util.stream.StreamSupport.stream(root.relativize(file).spliterator(), false)
            .map(Path::toString)
            .map(ProjectTemplate::undotPrefix)
            .reduce((a, b) -> a + "/" + b)
            .orElseThrow();
    try {
      byte[] bytes = Files.readAllBytes(file);
      if (relative.endsWith(SYMLINK_SUFFIX)) {
        // strip() is mandatory: any editor or hook that ensures a trailing newline would otherwise
        // make the link target literally "AGENTS.md\n" — a dangling symlink in every checkout.
        String target = new String(bytes, StandardCharsets.UTF_8).strip();
        return new TemplateEntry(
            relative.substring(0, relative.length() - SYMLINK_SUFFIX.length()),
            MODE_SYMLINK,
            target.getBytes(StandardCharsets.UTF_8));
      }
      return new TemplateEntry(relative, MODE_FILE, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read project template entry " + relative, e);
    }
  }
}
