package eu.wohlben.qits.projects.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in <b>this module</b>, as the
 * three keys a deployment would supply for the {@code projects} datasource: {@code jdbc.url},
 * {@code username}, {@code password}.
 *
 * <p>It is a config source rather than three lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over both the shipped defaults
 * in this jar's {@code META-INF/microprofile-config.properties} — which are an unresolvable {@code
 * ${QITS_RESOURCE_DB_URL}} expression under test — and anything the test properties file might
 * carry. It is registered through {@code META-INF/services}, which is how a config source joins a
 * Quarkus application without being a bean.
 *
 * <p><b>Named for the module, and not shipped in the test-jar.</b> {@code service} carries its own
 * source for the same two datasources at its own database names ({@code
 * ServiceEmbeddedPgConfigSource}); the class below still travels there on the test-jar classpath,
 * because that jar ships every test class, but the {@code META-INF/services} entry that would
 * activate it does not — the jar plugin excludes it. Only one source is ever registered in a run.
 */
public class DomainEmbeddedPgConfigSource implements ConfigSource {

  /** This module's database on the shared instance. Every (module, datasource) pair names its own. */
  private static final String DATABASE = "qp_domain_projects";

  private static final String PREFIX = "quarkus.datasource.projects.";

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
