package eu.wohlben.qits.epics.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply for the {@code epics} datasource: {@code jdbc.url}, {@code
 * username}, {@code password}.
 *
 * <p>It is a config source rather than three lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over the shipped default in
 * this jar's {@code META-INF/microprofile-config.properties}, which under test is an unresolvable
 * {@code ${QITS_RESOURCE_EPICS_URL}} expression. It is registered through {@code META-INF/services},
 * which is how a config source joins a Quarkus application without being a bean.
 */
public class EpicsEmbeddedPgConfigSource implements ConfigSource {

  /** This module's database on the shared instance. Every (module, datasource) pair names its own. */
  private static final String DATABASE = "qp_epics_epics";

  private static final String PREFIX = "quarkus.datasource.epics.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD);

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
