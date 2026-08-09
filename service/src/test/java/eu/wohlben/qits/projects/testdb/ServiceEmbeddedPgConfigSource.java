package eu.wohlben.qits.projects.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in the deployable's suite, as
 * the six keys a deployment supplies for the two datasources this application opens — {@code
 * projects} (domain's) and {@code epics}.
 *
 * <p>It is a config source rather than six lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time: the embedded
 * instance takes a free one, so nothing can be written down ahead of the JVM that starts it. The
 * ordinal sits above application.properties (250), so this wins over the two library jars' shipped
 * defaults, which under test are unresolvable {@code ${QITS_RESOURCE_*_URL}} expressions.
 *
 * <p><b>Two databases on one server, as in production.</b> Their names differ from {@code domain}'s
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

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.projects.jdbc.url", EmbeddedPg.url(PROJECTS_DATABASE),
          "quarkus.datasource.projects.username", EmbeddedPg.USER,
          "quarkus.datasource.projects.password", EmbeddedPg.PASSWORD,
          "quarkus.datasource.epics.jdbc.url", EmbeddedPg.url(EPICS_DATABASE),
          "quarkus.datasource.epics.username", EmbeddedPg.USER,
          "quarkus.datasource.epics.password", EmbeddedPg.PASSWORD);

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
