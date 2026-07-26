package eu.wohlben.qits.projects.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class ProjectSlugValidator implements ConstraintValidator<ProjectSlug, String> {

  /** Compiled once — {@link #isValid} runs on every project create/update request. */
  private static final Pattern SLUG = Pattern.compile(ProjectSlug.PATTERN);

  /** Whether {@code value} is a well-formed project slug. Null is valid; blank is not. */
  public static boolean matches(String value) {
    return value != null && SLUG.matcher(value).matches();
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || matches(value);
  }
}
