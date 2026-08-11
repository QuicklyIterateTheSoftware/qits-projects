package eu.wohlben.qits.projects;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Every postgresql datasource this application deploys with carries the resilience baseline: the
 * patient driver, validation at borrow, and a 15s acquisition budget. A service that drops one line
 * survives a postgres cutover no better than stock Agroal, and the loss is invisible until the next
 * one — so it fails the build here instead.
 *
 * <p><b>In {@code service}, and only here.</b> This module is the deployable, and its config is the
 * only one that sees all three datasources at once: {@code projects} from {@code domain}'s shipped
 * defaults, {@code epics} from the sibling jar's, and {@code eventstream} from the bus jar's. The
 * ArchUnit half of the shared rules goes the other way — one {@code ArchRulesTest} per entity module
 * — because those rules judge classes a module owns, and this one judges configuration only the
 * application has all of.
 *
 * <p>A {@code @QuarkusTest} rather than a plain unit test, and that is load-bearing: {@code
 * application.properties} is a Quarkus config source, so a bare {@code ConfigProvider.getConfig()}
 * would read the jars' defaults and none of this module's own lines.
 */
@QuarkusTest
public class DatasourceBaselineTest {

  @Test
  public void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
