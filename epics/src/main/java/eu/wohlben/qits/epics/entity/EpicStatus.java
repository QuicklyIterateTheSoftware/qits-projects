package eu.wohlben.qits.epics.entity;

/**
 * The phase an {@link Epic} is in. Stored as the enum name (V3), and the only thing that decides
 * which mutations the services accept — see {@code EpicLifecycle}.
 *
 * <p>"Done" used to be derived only — {@link #IMPLEMENTATION} with every feature's {@code
 * implementedOn} set — and deliberately not stored, so the same fact could not have two sources
 * that disagree. {@link #IMPLEMENTED} stores it now, and answers that objection at the transition
 * instead: the move stamps every unimplemented feature and task in the same transaction, so the
 * stored status and the derived reading agree the moment either exists. What forced the change is
 * the epic with no features at all — implemented directly from its description, with nothing for
 * the derivation to fire on, it sat in IMPLEMENTATION forever with no way to say it had shipped.
 */
public enum EpicStatus {

  /** The draft: title, description, features and tasks are all mutable. New epics start here. */
  REFINING,

  /** Scope is frozen: only {@code implementedOn}/{@code implementedAt} still move. */
  IMPLEMENTATION,

  /**
   * Shipped. Entered through the transition endpoint, which stamps every feature and task still
   * unimplemented — declaring the epic done is declaring its scope done, one writer, one moment.
   * Everything is frozen here; the one move left is {@link #SUPERSEDED}, for the successor that
   * revisits a shipped scope.
   */
  IMPLEMENTED,

  /**
   * Back to the drawing board. The row keeps its frozen scope as the record of what was discarded
   * and points at the successor draft it spawned ({@link Epic#supersededByEpicId}).
   */
  SUPERSEDED,

  /** Terminal: will not be implemented. */
  ABANDONED
}
