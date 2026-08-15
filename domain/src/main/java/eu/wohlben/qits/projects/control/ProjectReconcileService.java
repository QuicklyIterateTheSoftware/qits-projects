package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.ProjectReconciliation.DomainAssertion;
import eu.wohlben.qits.projects.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Iterator;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The manual drift remedy: re-assert a project's stored dns record through {@link
 * ProjectDomainRegistrar} <b>synchronously</b>, and report what actually happened
 * (main-environment-plan.md §5, "Automatic drift healing").
 *
 * <p>NOTE: nothing implements that port today, so every reconcile answers {@link #NO_REGISTRAR}
 * below. That is the honest answer, not a defect — see the port's javadoc.
 *
 * <p>It exists because {@link ProjectService}'s creation hook is fire-and-forget. A creation, unlike
 * an event stream, has no next event to carry a missed registration forward, so a project whose
 * record never appeared stays that way — and a warn line in an unwatched log is not a remedy. A step
 * a person invokes and whose result they can read is. The receiver being idempotent is what makes
 * re-asserting legitimate rather than reckless, and it doubles as the retro-fire for every project
 * created before the hook existed, the already-seeded {@code qits} project included.
 *
 * <p><b>Its own service and not another method on {@link ProjectService}</b>, which is already the
 * largest thing in this package that is not {@code RepositoryService}: this drives the port without
 * owning the aggregate, and a periodic or startup reconcile — explicitly a later leg — layers onto
 * this seam rather than onto the creation path.
 *
 * <p>It used to re-assert a deployment environment against qits-cd too. qits-cd owns environments
 * now — deliberate tiers created over its REST surface, not one per project — so a project has one
 * fact left to reconcile.
 *
 * <p>Nothing here is transactional and nothing here writes: the project's stored record is the
 * input, the outcome is the output, and the row is untouched either way. A reconcile is a
 * re-assertion, not a repair of local state.
 */
@ApplicationScoped
public class ProjectReconcileService {

  private static final Logger LOG = Logger.getLogger(ProjectReconcileService.class);

  /**
   * What an absent port implementation reports. Absent is a supported configuration for every port
   * here — but a reconcile that asserted nothing must not answer {@code REGISTERED}, so it is a
   * FAILED that says exactly what is missing rather than a cheerful lie or an exception.
   */
  static final String NO_REGISTRAR =
      "No ProjectDomainRegistrar is wired in this deployment, so no record was registered.";

  static final String NO_RECORD =
      "This project stores no dns record, so there is no domain to register.";

  @Inject ProjectService projectService;

  // The same port ProjectService.announce fires, driven here through its synchronous half.
  @Inject Instance<ProjectDomainRegistrar> domainRegistrars;

  /**
   * Re-assert the project's dns record and answer with the outcome.
   *
   * <p>An unknown project is the <b>only</b> error: it is the one thing about the request that is
   * wrong, whereas a receiver being down is a result the caller asked for.
   *
   * @throws eu.wohlben.qits.projects.error.NotFoundException if no such project exists (404)
   */
  public ProjectReconciliation reconcile(String projectId) {
    Project project = projectService.get(projectId);
    DomainAssertion domain = assertDomain(domainRegistrars, project);
    LOG.infof("Reconciled project %s (%s): domain %s.", project.id, project.slug, domain.outcome());
    return new ProjectReconciliation(domain);
  }

  /**
   * Drives the registrar port, or says that the project has no domain / that none is wired.
   *
   * <p>Static and taking an {@link Iterable} rather than reading the injected field: {@code
   * Instance<T>} <em>is</em> an {@code Iterable<T>}, so the container hands its candidates straight
   * in, and the two answers a container cannot easily be talked into producing — no implementation
   * at all, an implementation that throws — become a plain unit test.
   *
   * <p>"No stored record" is decided <b>here</b> and never by the registrar: the absence is this
   * context's own fact — a row predating the columns, a self-seed with no dns configuration — and a
   * registrar handed three nulls could only guess at it. See {@link ProjectDomainRegistrar}.
   */
  static DomainAssertion assertDomain(
      Iterable<ProjectDomainRegistrar> registrars, Project project) {
    if (project.dns == null) {
      return DomainAssertion.notConfigured(NO_RECORD);
    }
    Optional<ProjectDomainRegistrar> registrar = first(registrars);
    if (registrar.isEmpty()) {
      return DomainAssertion.failed(NO_REGISTRAR);
    }
    try {
      DomainAssertion assertion =
          registrar
              .get()
              .registerNow(
                  project.id,
                  project.slug,
                  project.dns.domain,
                  project.dns.type,
                  project.dns.value);
      return assertion == null
          ? DomainAssertion.failed(
              registrar.get().getClass().getSimpleName() + " answered with no outcome.")
          : assertion;
    } catch (RuntimeException e) {
      LOG.warnf(e, "Domain re-assertion for project %s threw", project.id);
      return DomainAssertion.failed(e.toString());
    }
  }

  /**
   * The first candidate, or empty when there is none.
   *
   * <p>Not {@code Instance#get()}, which throws when more than one implementation is present. A
   * deployment has at most one registrar — none at all today — and a reconcile has one answer to
   * give, so "the first" is the rule rather than a fold over however many happen to be on the
   * classpath.
   */
  private static <T> Optional<T> first(Iterable<T> candidates) {
    Iterator<T> iterator = candidates.iterator();
    return iterator.hasNext() ? Optional.ofNullable(iterator.next()) : Optional.empty();
  }
}
