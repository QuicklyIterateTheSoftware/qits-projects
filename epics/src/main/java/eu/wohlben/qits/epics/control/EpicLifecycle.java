package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.error.ConflictException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The epic lifecycle rules, in one place because all three services obey them: {@link EpicService},
 * {@link FeatureService} and {@link TaskService} (a task's phase is its feature's epic's phase).
 *
 * <p>The freeze is field-aware, not endpoint-aware. A structural change — the epic's title or
 * description, and any feature/task create, update or delete, dependencies included — needs {@link
 * EpicStatus#REFINING}. The implemented markers ({@code implementedOn}/{@code implementedAt}) need
 * {@link EpicStatus#IMPLEMENTATION}. The two guards therefore reject everything in {@link
 * EpicStatus#IMPLEMENTED}, {@link EpicStatus#SUPERSEDED} and {@link EpicStatus#ABANDONED} without a
 * rule of their own.
 *
 * <p>Deleting an epic stays allowed in every status: it removes the row and its subtree rather than
 * changing a frozen scope, and the audit log outlives it.
 */
final class EpicLifecycle {

  /** What each status may move to. A status absent from the map is terminal. */
  private static final Map<EpicStatus, Set<EpicStatus>> LEGAL_TARGETS =
      Map.of(
          EpicStatus.REFINING,
          EnumSet.of(EpicStatus.IMPLEMENTATION, EpicStatus.ABANDONED),
          EpicStatus.IMPLEMENTATION,
          EnumSet.of(EpicStatus.IMPLEMENTED, EpicStatus.SUPERSEDED, EpicStatus.ABANDONED),
          EpicStatus.IMPLEMENTED,
          EnumSet.of(EpicStatus.SUPERSEDED));

  private EpicLifecycle() {}

  /** The status named by {@code value}, or empty when it names none. */
  static Optional<EpicStatus> parse(String value) {
    for (EpicStatus status : EpicStatus.values()) {
      if (status.name().equals(value)) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }

  /** Rejects a move the lifecycle does not allow, naming both ends. */
  static void requireTransition(EpicStatus from, EpicStatus target) {
    if (!LEGAL_TARGETS.getOrDefault(from, EnumSet.noneOf(EpicStatus.class)).contains(target)) {
      throw new ConflictException("An epic cannot move from " + from + " to " + target);
    }
  }

  /** Rejects a structural change to an epic whose scope is no longer a draft. */
  static void requireRefining(Epic epic) {
    if (epic.status != EpicStatus.REFINING) {
      throw new ConflictException(
          "The scope of epic " + epic.id + " is frozen: it is " + epic.status);
    }
  }

  /** Rejects an implemented-marker change to an epic that is not being implemented. */
  static void requireImplementation(Epic epic) {
    if (epic.status != EpicStatus.IMPLEMENTATION) {
      throw new ConflictException(
          "Implemented markers need an epic in IMPLEMENTATION: epic "
              + epic.id
              + " is "
              + epic.status);
    }
  }
}
