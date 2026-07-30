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
 * Validates a dns record's payload — the address for an {@code A}/{@code AAAA}, the target for a
 * {@code CNAME}.
 *
 * <p><b>Deliberately loose about form and strict about shape.</b> Whether {@code 203.0.113.9} is a
 * routable address, whether a CNAME target exists and whether an {@code AAAA} value parses as IPv6
 * are questions qits-dns answers, against its own type rules, at the moment of the write — checking
 * them a second time here would mean maintaining a second, worse copy of them and would drift the
 * day it gained a type. What is checked here is what makes the value a value at all: present,
 * non-blank, and free of whitespace and control characters, which is the only class of input that
 * could travel through this column into a JSON body and mean something other than it looks like.
 *
 * <p>Required for <b>every</b> type. A {@code CNAME} with no target is not a record, so there is
 * nothing sensible to default it to.
 *
 * <p>Null is valid, the contract every constraint in this package carries; requiredness belongs to
 * the containing field.
 */
@Documented
@Constraint(validatedBy = DnsRecordValueValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface DnsRecordValue {

  /** The wire cap a CNAME target shares with a domain name; an address is far shorter. */
  int MAX_LENGTH = 253;

  String message() default
      "must be a non-blank record value of at most 253 characters, with no whitespace or control"
          + " characters";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
