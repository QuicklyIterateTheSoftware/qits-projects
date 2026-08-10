package eu.wohlben.qits.projects.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in the deployable's suite, as
 * the nine keys a deployment supplies for the three datasources this application opens — {@code
 * projects} (domain's), {@code epics}, and {@code eventstream} (the event bus jar's outbox).
 *
 * <p>It is a config source rather than nine lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time: the embedded
 * instance takes a free one, so nothing can be written down ahead of the JVM that starts it. The
 * ordinal sits above application.properties (250), so this wins over the two library jars' shipped
 * defaults, which under test are unresolvable {@code ${QITS_RESOURCE_*_URL}} expressions.
 *
 * <p><b>Three databases on one server, as in production.</b> Their names differ from {@code domain}'s
 * and {@code epics}' own suite databases, so no two modules ever mean the same schema — the reason a
 * container would be named per module, applied to a database instead.
 *
 * <p>{@link EmbeddedPg} itself comes from {@code domain}'s test-jar, which this module already
 * depends on for the port implementations; only the mapping below is local. {@code domain} also
 * ships its own {@code DomainEmbeddedPgConfigSource} class into that jar, but not the {@code
 * META-INF/services} entry that would register it, so this is the only source active here.
 */
public class ServiceEmbeddedPgConfigSource implements ConfigSource {

  private static final String PROJECTS_DATABASE = "qp_svc_projects";
  private static final String EPICS_DATABASE = "qp_svc_epics";

  /**
   * The event bus's outbox and claim tables. Deliberately NOT {@code eventstream_test} — that is the
   * qits-eventstream library's own suite's database, and a consumer must not be able to mean it.
   *
   * <p>It is here because joining that jar turned this deployable into one that opens a third
   * datasource: {@code qits.eventstream.enabled=false} under {@code %test} stops publishing, sweeping
   * and dialling, and stops none of the connecting and migrating Quarkus does at boot. So the outbox
   * gets a database here or the whole suite fails to start.
   */
  private static final String EVENTSTREAM_DATABASE = "qp_svc_eventstream";

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.projects.jdbc.url", EmbeddedPg.url(PROJECTS_DATABASE),
          "quarkus.datasource.projects.username", EmbeddedPg.USER,
          "quarkus.datasource.projects.password", EmbeddedPg.PASSWORD,
          "quarkus.datasource.epics.jdbc.url", EmbeddedPg.url(EPICS_DATABASE),
          "quarkus.datasource.epics.username", EmbeddedPg.USER,
          "quarkus.datasource.epics.password", EmbeddedPg.PASSWORD,
          "quarkus.datasource.eventstream.jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          "quarkus.datasource.eventstream.username", EmbeddedPg.USER,
          "quarkus.datasource.eventstream.password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
