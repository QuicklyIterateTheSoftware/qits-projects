package eu.wohlben.qits.projects.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DnsRecordValueValidator implements ConstraintValidator<DnsRecordValue, String> {

  /**
   * Whether {@code value} can be a record payload. Null is valid; blank is not.
   *
   * <p>Character-by-character rather than a regex, because the rule is a character <em>class</em>
   * ("nothing whitespace, nothing control") and not a shape — an {@code isISOControl} test says
   * that in one word, while the equivalent character-class regex would need re-reading to be
   * believed.
   */
  public static boolean matches(String value) {
    if (value == null || value.isBlank() || value.length() > DnsRecordValue.MAX_LENGTH) {
      return false;
    }
    return value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || matches(value);
  }
}
