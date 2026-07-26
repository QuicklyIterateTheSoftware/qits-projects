package eu.wohlben.qits.projects.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Validates a project slug: the git-safe, immutable identity a project's wrapper repository is
 * named after ({@code <slug>-<slug>}).
 *
 * <p>The slug is concatenated into a git-servable path segment ({@code /git/<projectId>/<name>})
 * <em>and</em> a forge repository name, so it must round-trip through {@code
 * RepositoryNameRepository.basename()} unchanged: lowercase alphanumerics and inner dashes only —
 * no leading/trailing dash, no {@code .} or {@code ..}, no {@code .git} suffix, no {@code /},
 * {@code :}, whitespace or unicode.
 *
 * <p>Null is valid, so an omitted slug on create (derived from the project name instead) and a
 * partial-update DTO both pass — the same contract as {@link NotBlankIfPresent}.
 */
@Documented
@Constraint(validatedBy = ProjectSlugValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface ProjectSlug {

  /**
   * The single source of truth for the slug format, 1–40 characters.
   *
   * <p>{@code ProjectService} asserts against this too: the annotation only guards HTTP, and the
   * self-seed, both cli seeds and MCP all reach {@code create} without passing through Bean
   * Validation.
   *
   * <p>The trailing group is optional on purpose — without the {@code ?} a one-character slug
   * (which {@code ProjectService.slugify} can legitimately produce from a name like {@code "X"})
   * would be rejected by the very rule meant to accept it.
   */
  String PATTERN = "^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$";

  String message() default
      "must be 1-40 characters of lowercase letters, digits and inner dashes"
          + " (no leading or trailing dash)";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
