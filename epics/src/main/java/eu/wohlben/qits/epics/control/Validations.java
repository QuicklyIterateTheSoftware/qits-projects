package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.error.BadRequestException;

/** Small shared control-layer guards for the epics services. */
final class Validations {

  private Validations() {}

  /** Throws {@link BadRequestException} if {@code value} is null or blank. */
  static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new BadRequestException(field + " is required");
    }
  }
}
