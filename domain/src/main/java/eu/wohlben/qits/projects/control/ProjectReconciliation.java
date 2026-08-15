package eu.wohlben.qits.projects.control;

/**
 * What one reconcile actually <em>did</em>: the answer {@code POST
 * /projects/api/projects/{projectId}/reconcile} reports back (main-environment-plan.md §5,
 * "Automatic drift healing").
 *
 * <p><b>An outcome is not an error.</b> Creation's domain hook is fire-and-forget, so a missed
 * registration leaves nothing behind but a warn line in a log nobody is watching; this type exists
 * so the manual remedy answers with what happened instead. A failure is therefore a <em>value</em>
 * here and not an exception — the caller asked what happened, not for it to work.
 *
 * <p>One target, deliberately: a project used to announce a deployment environment too, and that
 * half is gone. qits-cd owns environments now — deliberate tiers created over its REST surface, not
 * one per project — so there is nothing here to re-assert on its behalf.
 *
 * <p>Two outcomes carry a decision worth naming:
 *
 * <ul>
 *   <li>{@link DomainOutcome#NOT_CONFIGURED} is decided <b>before</b> any port is called: a project
 *       with no stored record has no domain to assert, which is the documented state of a row
 *       predating the columns and of a self-seed with no dns configuration — not a failure.
 *   <li>{@link DomainOutcome#NO_MATCHING_ZONE} is the registrar's documented stop, promoted from a
 *       log line to a reportable outcome. A zone is delegated at a registrar (NS records, glue) and
 *       is not this platform's to invent, so "no zone contains this name" is an answer an operator
 *       acts on rather than a fault to retry.
 * </ul>
 *
 * <p>{@code detail} is prose for a human reading the response, never a code to branch on: null when
 * the outcome says everything, and otherwise the reason — a status code, an exception, the name
 * that matched no zone. It is {@linkplain #brief(String) bounded}, because it can end up carrying a
 * remote service's response text and a reconcile must not answer with a megabyte of somebody else's
 * html.
 */
public record ProjectReconciliation(DomainAssertion domain) {

  /**
   * The cap on a {@code detail}. Generous enough for a stack-frame-free exception message or a
   * sentence, short enough that a hostile or verbose receiver cannot make this response its own
   * transport.
   */
  static final int MAX_DETAIL = 200;

  /** What re-asserting the project's dns record through the registrar port came to. */
  public enum DomainOutcome {
    /** The record set for this name and type is now the stored one. */
    REGISTERED,
    /** No zone contains the name, so nothing was written — see the type javadoc. */
    NO_MATCHING_ZONE,
    /** The project stores no dns record, so there was nothing to assert. */
    NOT_CONFIGURED,
    /** It could not be asserted: nothing was wired, the receiver refused, or it was unreachable. */
    FAILED
  }

  /** The answer: the outcome, plus prose when the outcome alone does not explain itself. */
  public record DomainAssertion(DomainOutcome outcome, String detail) {

    public static DomainAssertion registered() {
      return new DomainAssertion(DomainOutcome.REGISTERED, null);
    }

    public static DomainAssertion noMatchingZone(String detail) {
      return new DomainAssertion(DomainOutcome.NO_MATCHING_ZONE, brief(detail));
    }

    public static DomainAssertion notConfigured(String detail) {
      return new DomainAssertion(DomainOutcome.NOT_CONFIGURED, brief(detail));
    }

    public static DomainAssertion failed(String detail) {
      return new DomainAssertion(DomainOutcome.FAILED, brief(detail));
    }
  }

  /** {@code detail}, truncated to {@link #MAX_DETAIL} and marked when it was cut. */
  static String brief(String detail) {
    if (detail == null) {
      return null;
    }
    String collapsed = detail.strip();
    if (collapsed.isEmpty()) {
      return null;
    }
    return collapsed.length() <= MAX_DETAIL ? collapsed : collapsed.substring(0, MAX_DETAIL) + "…";
  }
}
