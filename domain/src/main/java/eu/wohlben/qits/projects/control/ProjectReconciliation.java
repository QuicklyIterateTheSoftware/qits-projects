package eu.wohlben.qits.projects.control;

/**
 * What one reconcile actually <em>did</em>, per target: the answer {@code POST
 * /projects/api/projects/{projectId}/reconcile} reports back (main-environment-plan.md §5,
 * "Automatic drift healing").
 *
 * <p><b>An outcome is not an error.</b> Creation's two hooks are fire-and-forget, so a missed
 * environment or registration leaves nothing behind but a warn line in a log nobody is watching;
 * this type exists so the manual remedy answers with what happened instead. A target that failed is
 * therefore a <em>value</em> here and not an exception — a reconcile whose environment failed and
 * whose domain registered did half its job, and the caller has to be able to see which half.
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
public record ProjectReconciliation(EnvironmentAssertion environment, DomainAssertion domain) {

  /**
   * The cap on a {@code detail}. Generous enough for a stack-frame-free exception message or a
   * sentence, short enough that a hostile or verbose receiver cannot make this response its own
   * transport.
   */
  static final int MAX_DETAIL = 200;

  /** What re-asserting the project's {@code main} environment against qits-cd came to. */
  public enum EnvironmentOutcome {
    /** The environment did not exist and now does. */
    CREATED,
    /**
     * The environment was already there — the steady state, and the reason re-asserting is safe.
     */
    ALREADY_EXISTS,
    /** It could not be asserted: nothing was wired, the receiver refused, or it was unreachable. */
    FAILED
  }

  /** What re-asserting the project's dns record against qits-dns came to. */
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

  /**
   * One target's answer: the outcome, plus prose when the outcome alone does not explain itself.
   */
  public record EnvironmentAssertion(EnvironmentOutcome outcome, String detail) {

    public static EnvironmentAssertion created() {
      return new EnvironmentAssertion(EnvironmentOutcome.CREATED, null);
    }

    public static EnvironmentAssertion alreadyExists() {
      return new EnvironmentAssertion(EnvironmentOutcome.ALREADY_EXISTS, null);
    }

    public static EnvironmentAssertion failed(String detail) {
      return new EnvironmentAssertion(EnvironmentOutcome.FAILED, brief(detail));
    }
  }

  /** The registrar's counterpart of {@link EnvironmentAssertion}. */
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
