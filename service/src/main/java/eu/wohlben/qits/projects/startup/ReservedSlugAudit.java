package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.projects.control.ReservedSlugs;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.jboss.logging.Logger;

/**
 * Says, once per boot and loudly, whether any project already holds a {@linkplain ReservedSlugs
 * reserved} slug.
 *
 * <p>The create path refuses one, but the reservation can arrive <b>after</b> the project does:
 * {@code qits.projects.reserved-slugs} is configuration, seeded with the platform's environment
 * names, so a new environment reserves a word some project may already be sitting on. Nothing in
 * this service can fix that — a slug is {@code @Column(updatable = false)} because the wrapper
 * repository, the backup organisation and the agent container are all named after it — so the only
 * useful thing to do is to name it where an operator will see it. On today's platform the list is
 * empty: the sole project is {@code qits-qits}, whose slug is {@code qits}.
 *
 * <p><b>It never fails boot, and it never blocks it.</b> A collision is a reading of a live
 * platform, not a reason to refuse to serve one — the projects involved keep working for everything
 * except the {@code editor.<project>.<domain>} host, which is exactly the thing the operator has to
 * decide about. It runs on a virtual thread after startup for the reason {@link StartupSelfSeed}
 * does: the query goes through the patient postgres driver, which is allowed to wait far longer
 * than readiness should.
 *
 * <p>Unlike its two neighbours it carries <b>no launch-mode gate</b>. It reads one table and reaches
 * no network, so there is nothing here for a {@code quarkus:dev} session or a suite to set off — and
 * a gate would mean the check is never exercised except in production.
 */
@ApplicationScoped
public class ReservedSlugAudit {

  private static final Logger LOG = Logger.getLogger(ReservedSlugAudit.class);

  @Inject ProjectRepository projectRepository;

  @Inject ReservedSlugs reservedSlugs;

  void onStart(@Observes StartupEvent event) {
    Thread.ofVirtual().name("qits-reserved-slug-audit").start(this::auditQuietly);
  }

  /**
   * {@link #audit()}, with the database's own failures swallowed. An audit is a report about the
   * platform; a platform that cannot be asked yet is not a finding, and the next boot asks again.
   */
  void auditQuietly() {
    try {
      audit();
    } catch (RuntimeException e) {
      LOG.warn("The reserved-slug audit could not read the projects — retried on the next boot.", e);
    }
  }

  /**
   * Reads every project and logs the collisions.
   *
   * @return the colliding projects, slug order, so a caller (the suite) can assert on them
   */
  public List<Project> audit() {
    List<Project> colliding =
        QuarkusTransaction.requiringNew()
            .call(() -> collisions(projectRepository.listAll(), reservedSlugs::isReserved));

    if (colliding.isEmpty()) {
      LOG.debug("Reserved-slug audit: no project holds a reserved slug.");
      return colliding;
    }
    LOG.errorf(
        "RESERVED-SLUG COLLISION: %d project(s) hold a slug the platform reserves. A slug is"
            + " immutable — the wrapper repository, the backup organisation and the agent container"
            + " are named after it — so this cannot be corrected here and needs a decision.",
        colliding.size());
    for (Project project : colliding) {
      LOG.errorf(
          "  project %s ('%s') holds the reserved slug '%s' — %s",
          project.id,
          project.name,
          project.slug,
          reservedSlugs.refusal(project.slug).orElse("reserved"));
    }
    return colliding;
  }

  /**
   * The pure half: the projects whose slug {@code reserved} claims, in slug order so a log and an
   * assertion read the same twice running.
   */
  static List<Project> collisions(Collection<Project> projects, Predicate<String> reserved) {
    return projects.stream()
        .filter(p -> p.slug != null && reserved.test(p.slug))
        .sorted(Comparator.comparing(p -> p.slug))
        .toList();
  }
}
