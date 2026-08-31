package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The whole reserved-slug question, asked in one place: <b>is this slug a word the platform already
 * means something else by, and if so, why?</b>
 *
 * <p>The answer has two families and they are reserved for different reasons, which is why this
 * bean answers with a <em>message</em> rather than a boolean:
 *
 * <ul>
 *   <li><b>Routing segments</b> — {@link ProjectService#RESERVED_SLUGS}, static and compiled in. A
 *       slug is the first path segment of every address on every application host, and those hosts
 *       path-route every application's own segment too. That list's rationale is on the constant.
 *   <li><b>Platform environment names</b> — {@code qits.projects.reserved-slugs}, configured,
 *       seeded by the bootstrap with the environments it knows about (the runtime value arrives as
 *       {@code QITS_PROJECTS_RESERVED_SLUGS}). This family cannot be compiled in: environments are
 *       created and removed on a running platform, and a service that shipped the list would be
 *       wrong the day the platform gained a tier.
 * </ul>
 *
 * <p><b>The environment family is a HOST reading, not a path one</b>, and that is the whole reason
 * it exists. The web editor is served at {@code editor.<project>.<domain>} while every application
 * is served at {@code <app>.<environment>.<domain>}; the edge reads the <em>first two labels</em>
 * of the host to tell one from the other. So a project whose slug spells an environment name makes
 * {@code editor.dev.<domain>} parse as "the application {@code editor} in the environment {@code
 * dev}" — the project label does not survive the reading, and the editor opens on the wrong thing
 * or on nothing. An <em>application</em>-name collision is harmless by comparison (the first label
 * is {@code editor}, never the project's slug) and is deliberately not guarded here.
 *
 * <p><b>The effective set is the UNION</b>, and the two families keep their own reasons: a caller
 * refused for one is told which. Nothing merges them into a single list, because a message that
 * said "reserved" without saying why leaves the caller guessing which of two unrelated mechanisms
 * they walked into.
 *
 * <p>Empty is the shipped configuration and is a supported one — with no list configured this is
 * exactly {@link ProjectService#RESERVED_SLUGS} and nothing else.
 */
@ApplicationScoped
public class ReservedSlugs {

  /**
   * The platform's environment names, comma-separated. Unset ships, and unset means "the static set
   * only" rather than "no reservation at all" — see the class javadoc.
   *
   * <p>Read as an {@code Optional} on purpose: an empty env var (how absence arrives from a k8s
   * ConfigMap or an env file) is an empty Optional in SmallRye, so blank and unset are one case.
   */
  @ConfigProperty(name = "qits.projects.reserved-slugs")
  Optional<List<String>> configuredEnvironments;

  /**
   * The hand-built form, for a caller that has the list already — a test, or anything holding the
   * names rather than the configuration. The injected bean is a client proxy, so its field cannot
   * be assigned from outside.
   */
  public static ReservedSlugs forEnvironments(List<String> environments) {
    ReservedSlugs reserved = new ReservedSlugs();
    reserved.configuredEnvironments = Optional.ofNullable(environments);
    return reserved;
  }

  /**
   * The configured environment names, normalized: trimmed, lowercased and with blanks dropped. A
   * configured list arrives as one env var, so whitespace after a comma and a trailing newline are
   * ordinary rather than exceptional, and neither is a different word.
   */
  public Set<String> environmentNames() {
    Set<String> names = new LinkedHashSet<>();
    for (String raw : configuredEnvironments.orElse(List.of())) {
      if (raw == null) {
        continue;
      }
      String name = raw.trim().toLowerCase(Locale.ROOT);
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    return names;
  }

  /** Whether {@code slug} is reserved by either family — the question {@code isFree} asks. */
  public boolean isReserved(String slug) {
    return refusal(slug).isPresent();
  }

  /**
   * The 400 message for a <b>supplied</b> reserved slug — naming the word and the reason it cannot
   * be taken — or empty when the slug is free of both families.
   *
   * <p>Routing is answered first where a word is in both lists: it is the older reservation and its
   * consequence (a path segment shadowed by a route) bites on every host rather than on one.
   *
   * <p>A <b>derived</b> slug never reaches this message. It suffixes past a reserved word like any
   * other collision ({@code nextFreeSlug}), because the caller stated nothing about the value — so a
   * project named after an environment still creates, as {@code <name>-2}.
   */
  public Optional<String> refusal(String slug) {
    if (slug == null || slug.isBlank()) {
      return Optional.empty();
    }
    String candidate = slug.trim();
    if (ProjectService.RESERVED_SLUGS.contains(candidate)) {
      return Optional.of(
          "The slug '"
              + candidate
              + "' is reserved. A slug is the first path segment of every address on every"
              + " application host, and that segment already routes something else — a repository"
              + " category, a platform path, or an application's own segment. Choose another.");
    }
    if (environmentNames().contains(candidate.toLowerCase(Locale.ROOT))) {
      return Optional.of(
          "The slug '"
              + candidate
              + "' is reserved: it names a platform environment. The web editor is served at"
              + " editor.<project>.<domain> while every application is served at"
              + " <app>.<environment>.<domain>, and the edge tells the two apart by reading the"
              + " first two labels of the host — so 'editor."
              + candidate
              + ".<domain>' would be read as the application 'editor' in the environment '"
              + candidate
              + "' and this project's label would vanish. Choose another, or omit the field to have"
              + " one derived from the name.");
    }
    return Optional.empty();
  }
}
