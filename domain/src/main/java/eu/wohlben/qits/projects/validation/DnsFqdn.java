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
 * Validates a fully-qualified domain name: dot-separated dns labels, <b>at least two</b> of them.
 *
 * <p>The value reaches an authoritative nameserver's configuration API and becomes what a resolver
 * answers, so it is checked here rather than trusted to the receiver — not because qits-dns would
 * accept rubbish (it validates for real) but because a 400 from a service the caller never
 * addressed is a fire-and-forget log line, and the person who typed the name is gone by then.
 *
 * <p>Two labels minimum, because a single label is a zone apex a registrar delegates and not
 * something a project may claim. <b>Lowercase only</b>, deliberately: dns comparison is
 * case-insensitive, so {@code UPPER.CASE} is not wrong so much as a second spelling of one name —
 * and two spellings of one hostname in one column is the ambiguity that makes "did the record
 * change?" unanswerable. Normalising it silently would be the other option and is worse: the stored
 * value is what a later diff is read against, and quietly rewriting a caller's input makes that
 * diff lie.
 *
 * <p>Null is valid — the same contract as {@link ProjectSlug} and {@link NotBlankIfPresent}.
 * Requiredness is the containing field's business ({@code @NotNull} on the request's {@code dns}).
 */
@Documented
@Constraint(validatedBy = DnsFqdnValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface DnsFqdn {

  /**
   * The single source of truth for the label shape: lowercase letters, digits and inner hyphens,
   * 1–63 characters per label, two labels or more.
   *
   * <p>{@code ProjectService} asserts against this too — the annotation only guards HTTP, and the
   * self-seed reaches {@code create} without passing through Bean Validation.
   *
   * <p>The inner group is optional (the {@code ?}) for the same reason {@code
   * ProjectSlug.PATTERN}'s is: without it a one-character label, which {@code a.qits.eu}
   * legitimately has, would be rejected by the rule meant to accept it.
   */
  String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

  /** The whole name: one label, then one or more dot-prefixed labels. */
  String PATTERN = "^" + LABEL + "(?:\\." + LABEL + ")+$";

  /**
   * The wire cap on a domain name. Enforced in {@link DnsFqdnValidator} rather than folded into
   * {@link #PATTERN} as a lookahead, so that a name rejected for being long is distinguishable from
   * one rejected for its shape when reading the validator.
   */
  int MAX_LENGTH = 253;

  String message() default
      "must be a lowercase fully-qualified domain name of at least two dot-separated dns labels"
          + " (letters, digits and inner hyphens), at most 253 characters";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
