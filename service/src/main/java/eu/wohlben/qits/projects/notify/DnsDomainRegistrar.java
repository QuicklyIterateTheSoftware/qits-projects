package eu.wohlben.qits.projects.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.ProjectDomainRegistrar;
import eu.wohlben.qits.projects.control.ProjectReconciliation.DomainAssertion;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
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
 * <p><b>And the same two hops, waited on</b>, for the manual reconcile ({@link #registerNow},
 * main-environment-plan.md §5). The two paths differ in exactly one thing — whether the outcome
 * goes to a log or to a caller — and in nothing else: {@link #zonesRequest()}, {@link #resolve} and
 * {@link #recordRequest} are the single copy of the url building, the boundary rule and the
 * payload. Zone resolution in particular must never be duplicated: a second copy could drift into
 * repointing somebody else's hostname while the first stayed correct, and nothing would say so.
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

  /** Connect and exchange budgets, shared by both paths — see {@link CdEnvironmentNotifier}. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * An <b>instance</b> field, not a static one — see {@link CdEnvironmentNotifier}, which carries
   * the native-image reasoning in full.
   */
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

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

  /**
   * A resolved write: the zone a name fell in, and the zone-relative name to write in it. Internal
   * to this class and never on the wire, so — unlike {@link Zone} — it needs no reflection
   * registration.
   */
  record Target(Zone zone, String recordName) {}

  @Override
  public void register(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
    try {
      client
          .sendAsync(zonesRequest(), HttpResponse.BodyHandlers.ofString())
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

  /**
   * The reconcile's synchronous half: the same zone lookup and the same write, waited on, with
   * qits-dns' answers read as outcomes.
   *
   * <p>Every branch of the asynchronous path has a counterpart here, over the same shared helpers —
   * the unreadable zone list, the name no zone contains (no write attempted, exactly as at
   * creation), the receiver's refusal. The whole 2xx class is {@code REGISTERED} for the reason
   * {@link CdEnvironmentNotifier#ensureEnvironment} gives: reporting a successful write as a
   * failure is the more expensive way to be wrong.
   */
  @Override
  public DomainAssertion registerNow(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value) {
    HttpResponse<String> zones;
    try {
      zones = client.send(zonesRequest(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return DomainAssertion.failed("Interrupted while listing zones for " + domain + ".");
    } catch (IOException e) {
      return DomainAssertion.failed("qits-dns at " + dnsUrl + ZONES_PATH + " is unreachable: " + e);
    }
    if (zones.statusCode() / 100 != 2) {
      return DomainAssertion.failed(
          "qits-dns answered " + zones.statusCode() + " listing zones for " + domain + ".");
    }
    Target target;
    try {
      target = resolve(domain, objectMapper.readValue(zones.body(), ZoneList.class));
    } catch (Exception e) {
      return DomainAssertion.failed("qits-dns' zone list could not be read: " + e);
    }
    if (target == null) {
      // The documented stop, said out loud this time: an operator delegates the zone at a registrar
      // and runs this again.
      return DomainAssertion.noMatchingZone(
          "No qits-dns zone contains "
              + domain
              + ". Create the zone and delegate it, then reconcile again.");
    }

    HttpRequest write;
    try {
      write = recordRequest(target, type, value);
    } catch (JsonProcessingException e) {
      return DomainAssertion.failed("Could not build the record request: " + e);
    }
    HttpResponse<Void> response;
    try {
      response = client.send(write, HttpResponse.BodyHandlers.discarding());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return DomainAssertion.failed("Interrupted while registering " + domain + ".");
    } catch (IOException e) {
      return DomainAssertion.failed("qits-dns became unreachable writing the record: " + e);
    }
    if (response.statusCode() / 100 == 2) {
      return DomainAssertion.registered();
    }
    return DomainAssertion.failed(
        "qits-dns answered "
            + response.statusCode()
            + " writing "
            + type
            + " "
            + target.recordName()
            + " in zone "
            + target.zone().fqdn()
            + ".");
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
    Target target;
    try {
      target = resolve(domain, objectMapper.readValue(response.body(), ZoneList.class));
    } catch (Exception e) {
      LOG.warnf(
          "Domain registration of %s for project %s: unreadable zone list: %s",
          domain, projectId, e);
      return;
    }
    if (target == null) {
      // The documented stop. A zone is delegated at a registrar, not created because a project
      // asked. The reconcile endpoint is named because this warning is otherwise the whole
      // notification, and it is a fire-and-forget one nobody will re-fire.
      LOG.warnf(
          "No qits-dns zone contains %s, so project '%s' (%s) registers no domain. Create the zone"
              + " and delegate it, then POST /projects/api/projects/%s/reconcile.",
          domain, slug, projectId, projectId);
      return;
    }
    put(target, projectId, domain, type, value);
  }

  private void put(
      Target target, String projectId, String domain, ProjectDnsRecordType type, String value) {
    try {
      client
          .sendAsync(recordRequest(target, type, value), HttpResponse.BodyHandlers.discarding())
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
                      type, domain, target.zone().fqdn(), projectId);
                }
              });
    } catch (Exception e) {
      LOG.warnf("Domain registration of %s for project %s skipped: %s", domain, projectId, e);
    }
  }

  /** The zone lookup both paths send. */
  private HttpRequest zonesRequest() {
    return HttpRequest.newBuilder(URI.create(dnsUrl + ZONES_PATH))
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/json")
        .GET()
        .build();
  }

  /** The record write both paths send, token header included when one is configured. */
  private HttpRequest recordRequest(Target target, ProjectDnsRecordType type, String value)
      throws JsonProcessingException {
    // LinkedHashMap and not Map.of: `ttl` is deliberately null — "follows the server default",
    // which
    // is what qits-dns stores a record with no override as — and Map.of rejects a null value.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", target.recordName());
    payload.put("type", type.name());
    payload.put("values", List.of(value));
    payload.put("ttl", null);

    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(dnsUrl + ZONES_PATH + "/" + target.zone().id() + "/records"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
    token().ifPresent(t -> request.header(TOKEN_HEADER, t));
    return request.build();
  }

  /** The configured token, or empty when blank — in which case no header is sent at all. */
  private Optional<String> token() {
    return configuredToken.map(String::trim).filter(t -> !t.isEmpty());
  }

  /**
   * Where {@code domain} is to be written: the zone it falls in and its zone-relative name, or null
   * when no zone contains it.
   *
   * <p>The <b>one</b> copy of the resolution, serving the creation hook and the reconcile alike.
   * Lowercasing happens here so the two paths cannot end up disagreeing about what they compared.
   */
  static Target resolve(String domain, ZoneList listed) {
    String normalized = domain.toLowerCase(Locale.ROOT);
    Zone zone = resolveZone(normalized, listed == null ? null : listed.zones());
    return zone == null
        ? null
        : new Target(zone, recordName(normalized, zone.fqdn().toLowerCase(Locale.ROOT)));
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
