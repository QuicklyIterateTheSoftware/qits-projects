package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReservedSlugs;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The boot-time report on projects already holding a reserved slug — the case the create path
 * cannot prevent, because {@code qits.projects.reserved-slugs} can grow after a project exists and
 * a slug is immutable.
 *
 * <p>The collision is staged by persisting a row directly rather than through {@code
 * ProjectService.create}, which is the point: the service refuses exactly this, so the only way a
 * platform reaches the state is for the reservation to arrive second. A word from the <b>static</b>
 * family stands in for one from the configured list, so this needs no {@code @TestProfile} — the
 * audit asks {@link ReservedSlugs}, which does not distinguish them.
 */
@QuarkusTest
public class ReservedSlugAuditTest {

  @Inject ReservedSlugAudit audit;

  @Inject ProjectRepository projectRepository;

  /** The state of a healthy platform, and today's real one: no project holds a reserved word. */
  @Test
  public void aPlatformWithNoCollisionReportsNothing() {
    assertTrue(audit.audit().isEmpty());
  }

  /** A row that got there before the reservation did is found, and named. */
  @Test
  public void aProjectSittingOnAReservedSlugIsReported() {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = id;
              project.name = "Predates The Reservation";
              project.slug = "projects";
              projectRepository.persist(project);
            });

    List<Project> colliding = audit.audit();

    assertEquals(1, colliding.size());
    assertEquals(id, colliding.get(0).id);
    assertEquals("projects", colliding.get(0).slug);
  }

  /**
   * The pure half, so the ordering the log and any assertion rely on is stated rather than
   * inherited from whatever the database returned.
   */
  @Test
  public void collisionsAreFilteredAndSlugOrdered() {
    List<Project> found =
        ReservedSlugAudit.collisions(
            List.of(project("b-prod"), project("checkout"), project("a-dev"), nullSlugged()),
            slug -> slug.endsWith("dev") || slug.endsWith("prod"));

    assertEquals(List.of("a-dev", "b-prod"), found.stream().map(p -> p.slug).toList());
  }

  private static Project project(String slug) {
    Project project = new Project();
    project.id = slug;
    project.name = slug;
    project.slug = slug;
    return project;
  }

  /** A slug is never null on a persisted row; the filter says so rather than assuming it. */
  private static Project nullSlugged() {
    Project project = new Project();
    project.id = "no-slug";
    project.name = "No Slug";
    return project;
  }
}
