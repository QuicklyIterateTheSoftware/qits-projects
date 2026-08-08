package eu.wohlben.qits.epics.entity;

/**
 * The phase an {@link Epic} is in. Stored as the enum name (V3), and the only thing that decides
 * which mutations the services accept — see {@code EpicLifecycle}.
 *
 * <p>There is deliberately no {@code DONE} member. "Done" is derived, not stored: an epic in {@link
 * #IMPLEMENTATION} with at least one feature and every feature's {@code implementedOn} set. Adding
 * it here would give the same fact two sources that can disagree.
 */
public enum EpicStatus {

  /** The draft: title, description, features and tasks are all mutable. New epics start here. */
  REFINING,

  /** Scope is frozen: only {@code implementedOn}/{@code implementedAt} still move. */
  IMPLEMENTATION,

  /**
   * Back to the drawing board. The row keeps its frozen scope as the record of what was discarded
   * and points at the successor draft it spawned ({@link Epic#supersededByEpicId}).
   */
  SUPERSEDED,

  /** Terminal: will not be implemented. */
  ABANDONED
}
