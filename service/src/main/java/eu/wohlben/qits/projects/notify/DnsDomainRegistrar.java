package eu.wohlben.qits.projects.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.ProjectDomainRegistrar;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Registers a project's domain with qits-dns: resolve the zone the name falls in, then replace that
 * name's record set.
 *
 * <p>Two hops, because only qits-dns knows which zones exist:
 *
 * <ol>
 *   <li>{@code GET /dns/api/zones} — pick the zone whose fqdn <b>equals</b> the domain or is its
 *       longest {@code .}-boundary suffix. The boundary is the whole trick: {@code notqits.eu} ends
 *       with the characters of {@code qits.eu} and is a different name entirely, so the test is
 *       {@code domain.endsWith("." + zone)} and never a bare {@code endsWith}. Longest wins, so a
 *       delegated {@code dev.qits.eu} beats its parent {@code qits.eu} for {@code app.dev.qits.eu}.
 *   <li>{@code PUT /dns/api/zones/{zoneId}/records} with the zone-relative name ({@code @} when the
 *       domain <em>is</em> the apex, otherwise the prefix left over), the type, and the single
 *       value in a list. {@code PUT} is replace-by-{@code (name, type)}: re-registering the same
 *       domain is a 200 rather than a 409 to dance around, which is what makes this safe to call on
 *       every creation.
 * </ol>
 *
 * <p><b>No zone matches ⇒ one warning and stop.</b> A zone is a registrar-level fact — NS
 * delegation and glue records live outside this platform — so a zone invented here would be one
 * nothing on the internet points at. One warning and not one per attempt, because the operator's
 * action is the same either way and the log line is the whole notification.
 *
 * <p><b>Fire-and-forget</b> and fully asynchronous, the {@code CdBuildNotifier} idiom carried
 * through two requests: the zone lookup's continuation issues the write, so the thread that just
 * created a project is never parked on either. Failures are logged — a warning, because a project
 * is created once and no later event will retry this.
 *
 * <p>{@code X-DNS-Token} rides along when {@code qits.dns.token} is set. Blank is the shipped
 * default and matches the receiver's own open mode, in which case <b>no header is sent at all</b>
 * rather than an empty one.
 */
@ApplicationScoped
public class DnsDomainRegistrar implements ProjectDomainRegistrar {

  private static final Logger LOG = Logger.getLogger(DnsDomainRegistrar.class);

  /** qits-dns' own paths, appended to the configured base — see {@link #dnsUrl}. */
  static final String ZONES_PATH = "/dns/api/zones";

  /** The guard header qits-dns' {@code DnsTokenFilter} reads on every write. */
  static final String TOKEN_HEADER = "X-DNS-Token";

  /** The zone-relative spelling of a name that <em>is</em> the zone apex. */
  static final String APEX = "@";

  /**
   * An <b>instance</b> field, not a static one — see {@link CdEnvironmentNotifier}, which carries
   * the native-image reasoning in full.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  /**
   * Scheme, host and port — <b>no path</b>. qits-dns' paths belong to this code, not to a
   * deployment.
   */
  @ConfigProperty(name = "qits.dns.url")
  String dnsUrl;

  /**
   * {@code Optional<String>} and not {@code String}: SmallRye Config reads an empty value as UNSET,
   * so a plain {@code String} injection of a key this repo ships BLANK fails the whole deployment
   * at boot — the documented default would be the thing that broke the app. qits-dns' own {@code
   * DnsTokenFilter} carries the same note about the same key.
   */
  @ConfigProperty(name = "qits.dns.token")
  Optional<String> configuredToken;

  @Inject ObjectMapper objectMapper;

  /**
   * One zone as this caller needs it. Only the two fields the resolution uses — a zone's serial and
   * timestamps are on the wire and are none of this code's business.
   *
   * <p>{@code @RegisterForReflection} is load-bearing for the same reason {@code
   * RepositoryMetadata}'s is: nothing in a route reaches this type, so Quarkus cannot see it, and
   * in a native image Jackson would find no constructor and every registration would fail with
   * "cannot deserialize" while the JVM suite stayed green.
   */
  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Zone(String id, String fqdn) {}

  /** The {@code GET /dns/api/zones} envelope. Registered for reflection for the same reason. */
  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ZoneList(List<Zone> zones) {}

  @Override
  public void register(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
    try {
      HttpRequest zones =
          HttpRequest.newBuilder(URI.create(dnsUrl + ZONES_PATH))
              .timeout(Duration.ofSeconds(10))
              .header("Accept", "application/json")
              .GET()
              .build();
      client
          .sendAsync(zones, HttpResponse.BodyHandlers.ofString())
          .thenAccept(response -> onZones(response, projectId, slug, domain, type, value))
          .exceptionally(
              failure -> {
                LOG.warnf(
                    "Domain registration of %s for project %s failed: %s",
                    domain, projectId, failure.toString());
                return null;
              });
    } catch (Exception e) {
      LOG.warnf("Domain registration of %s for project %s skipped: %s", domain, projectId, e);
    }
  }

  private void onZones(
      HttpResponse<String> response,
      String projectId,
      String slug,
      String domain,
      ProjectDnsRecordType type,
      String value) {
    if (response.statusCode() >= 400) {
      LOG.warnf(
          "Domain registration of %s for project %s: qits-dns answered %d listing zones.",
          domain, projectId, response.statusCode());
      return;
    }
    ZoneList listed;
    try {
      listed = objectMapper.readValue(response.body(), ZoneList.class);
    } catch (Exception e) {
      LOG.warnf(
          "Domain registration of %s for project %s: unreadable zone list: %s",
          domain, projectId, e);
      return;
    }
    String normalized = domain.toLowerCase(Locale.ROOT);
    Zone zone = resolveZone(normalized, listed.zones());
    if (zone == null) {
      // The documented stop. A zone is delegated at a registrar, not created because a project
      // asked.
      LOG.warnf(
          "No qits-dns zone contains %s, so project '%s' (%s) registers no domain. Create the zone"
              + " and delegate it, then re-register the record.",
          domain, slug, projectId);
      return;
    }
    put(
        zone,
        recordName(normalized, zone.fqdn().toLowerCase(Locale.ROOT)),
        projectId,
        domain,
        type,
        value);
  }

  private void put(
      Zone zone,
      String recordName,
      String projectId,
      String domain,
      ProjectDnsRecordType type,
      String value) {
    try {
      // LinkedHashMap and not Map.of: `ttl` is deliberately null — "follows the server default",
      // which
      // is what qits-dns stores a record with no override as — and Map.of rejects a null value.
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("name", recordName);
      payload.put("type", type.name());
      payload.put("values", List.of(value));
      payload.put("ttl", null);

      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(dnsUrl + ZONES_PATH + "/" + zone.id() + "/records"))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
      token().ifPresent(t -> request.header(TOKEN_HEADER, t));

      client
          .sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
          .whenComplete(
              (response, failure) -> {
                if (failure != null) {
                  LOG.warnf(
                      "Domain registration of %s for project %s failed: %s",
                      domain, projectId, failure.toString());
                } else if (response.statusCode() >= 400) {
                  LOG.warnf(
                      "Domain registration of %s for project %s rejected: %d",
                      domain, projectId, response.statusCode());
                } else {
                  LOG.debugf(
                      "Registered %s %s in zone %s for project %s.",
                      type, domain, zone.fqdn(), projectId);
                }
              });
    } catch (Exception e) {
      LOG.warnf("Domain registration of %s for project %s skipped: %s", domain, projectId, e);
    }
  }

  /** The configured token, or empty when blank — in which case no header is sent at all. */
  private Optional<String> token() {
    return configuredToken.map(String::trim).filter(t -> !t.isEmpty());
  }

  /**
   * The zone {@code domain} falls in: an exact match on the apex, or the <b>longest</b> zone that
   * is a {@code .}-boundary suffix of it. Null when none is.
   *
   * <p>Package-private and static so {@code DnsDomainRegistrarTest} can pin the boundary rule
   * without a server: it is the one piece of arithmetic here that a wrong answer to would repoint
   * somebody else's hostname.
   */
  static Zone resolveZone(String domain, List<Zone> zones) {
    if (zones == null) {
      return null;
    }
    Zone best = null;
    int bestLength = -1;
    for (Zone zone : zones) {
      if (zone == null || zone.fqdn() == null || zone.id() == null) {
        continue;
      }
      String fqdn = zone.fqdn().toLowerCase(Locale.ROOT);
      if (!domain.equals(fqdn) && !domain.endsWith("." + fqdn)) {
        continue;
      }
      if (fqdn.length() > bestLength) {
        best = zone;
        bestLength = fqdn.length();
      }
    }
    return best;
  }

  /**
   * {@code domain} minus the zone apex: {@code @} when they are equal, the leftover prefix
   * otherwise.
   */
  static String recordName(String domain, String zoneFqdn) {
    return domain.equals(zoneFqdn)
        ? APEX
        : domain.substring(0, domain.length() - zoneFqdn.length() - 1);
  }
}
