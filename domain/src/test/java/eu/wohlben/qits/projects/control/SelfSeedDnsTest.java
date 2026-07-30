package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import eu.wohlben.qits.projects.testsupport.RecordingProjectDomainRegistrar;
import eu.wohlben.qits.projects.testsupport.RecordingProjectEnvironmentNotifier;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The seed's dns configuration (main-environment-plan.md §3): with {@code
 * qits.startup-seed.dns-domain}/{@code -type}/{@code -value} all set, the seeded {@code qits}
 * project stores the record and registers it.
 *
 * <p>A class of its own because a {@code @TestProfile} is per class and the absence case — the
 * shipped default, three keys unset — has to be a profile that does not carry them; that half lives
 * in {@link SelfSeedServiceTest}. The manifest urls are redirected to the same committed fixtures
 * for the same reason they are there.
 */
@QuarkusTest
@TestProfile(SelfSeedDnsTest.DnsConfigured.class)
public class SelfSeedDnsTest {

  static final String DOMAIN = "seeded.test.eu";
  static final String VALUE = "203.0.113.42";

  /** {@link SelfSeedServiceTest.TestProfile}'s fixtures plus the three dns keys. */
  public static class DnsConfigured extends SelfSeedServiceTest.TestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
      overrides.put("qits.startup-seed.dns-domain", DOMAIN);
      // Lowercase on purpose: the seed uppercases the type before parsing it, because an env var
      // spelled `a` is a person typing a record type rather than a mistake.
      overrides.put("qits.startup-seed.dns-type", "cname");
      overrides.put("qits.startup-seed.dns-value", VALUE);
      return overrides;
    }
  }

  @Inject SelfSeedService selfSeedService;
  @Inject ProjectService projectService;
  @Inject RecordingProjectEnvironmentNotifier environments;
  @Inject RecordingProjectDomainRegistrar domains;

  @BeforeEach
  void clean() {
    List.copyOf(projectService.list()).forEach(p -> projectService.delete(p.id));
    environments.clear();
    domains.clear();
  }

  private Project qitsProject() {
    var projects = projectService.list().stream().filter(p -> "qits".equals(p.name)).toList();
    assertEquals(1, projects.size(), "exactly one 'qits' project");
    return projects.get(0);
  }

  @Test
  public void theSeededProjectStoresAndRegistersTheConfiguredDomain() {
    selfSeedService.reconcile();

    Project project = qitsProject();
    assertEquals(DOMAIN, project.dns.domain);
    assertEquals(ProjectDnsRecordType.CNAME, project.dns.type);
    assertEquals(VALUE, project.dns.value);

    assertTrue(environments.announcementFor(project.id).isPresent(), "and its environment too");
    var registration = domains.registrationFor(project.id).orElseThrow();
    assertEquals(DOMAIN, registration.domain());
    assertEquals(ProjectDnsRecordType.CNAME, registration.type());
    assertEquals(VALUE, registration.value());
  }

  /**
   * A partially configured record is a typo, and a typo in one env var must not take the seed — and
   * with it every repository registration — down. It is read as "no domain" and said so once.
   *
   * <p>Against a hand-built instance rather than the injected bean: the injected one is a client
   * proxy, so assigning its fields would write to the proxy and prove nothing, and rewriting shared
   * application state mid-suite would leak into the reconcile tests above.
   */
  @Test
  public void aPartiallyConfiguredRecordIsReadAsNoDomain() {
    assertNull(reader(DOMAIN, null, VALUE).seededDnsRecord(), "no type");
    assertNull(reader(DOMAIN, "A", null).seededDnsRecord(), "no value");
    assertNull(reader(null, "A", VALUE).seededDnsRecord(), "no domain");
    // Blank is not "set": a k8s ConfigMap key with an empty value is how absence usually arrives.
    assertNull(reader(DOMAIN, "  ", VALUE).seededDnsRecord(), "blank type");
  }

  /** Same rule for a type that is not one of the three. */
  @Test
  public void anUnparseableTypeIsReadAsNoDomain() {
    assertNull(reader(DOMAIN, "SRV", VALUE).seededDnsRecord());
  }

  /** Nothing configured at all — the shipped default, and the only silent one of these cases. */
  @Test
  public void nothingConfiguredIsReadAsNoDomain() {
    assertNull(reader(null, null, null).seededDnsRecord());
  }

  private static SelfSeedService reader(String domain, String type, String value) {
    SelfSeedService reader = new SelfSeedService();
    reader.dnsDomain = java.util.Optional.ofNullable(domain);
    reader.dnsType = java.util.Optional.ofNullable(type);
    reader.dnsValue = java.util.Optional.ofNullable(value);
    return reader;
  }
}
