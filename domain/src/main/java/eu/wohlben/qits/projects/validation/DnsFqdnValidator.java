package eu.wohlben.qits.projects.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class DnsFqdnValidator implements ConstraintValidator<DnsFqdn, String> {

  /** Compiled once — {@link #isValid} runs on every project create request. */
  private static final Pattern FQDN = Pattern.compile(DnsFqdn.PATTERN);

  /** Whether {@code value} is a well-formed fqdn. Null is valid; blank is not. */
  public static boolean matches(String value) {
    return value != null && value.length() <= DnsFqdn.MAX_LENGTH && FQDN.matcher(value).matches();
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || matches(value);
  }
}
