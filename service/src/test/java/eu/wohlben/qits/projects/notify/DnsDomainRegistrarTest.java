package eu.wohlben.qits.projects.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wire half of the domain registration, against a local server standing in for qits-dns: the
 * zone lookup, the boundary rule that picks a zone, the {@code @}-versus-label spelling, the token
 * header, and the silence when no zone contains the name.
 *
 * <p>Plain JUnit over a directly-constructed registrar — the seam's semantics (which creations
 * register, with which record) are held in {@code ProjectCreationHooksTest}. Both requests are
 * asserted at their absolute paths, because the failure mode here is silence: a path that stopped
 * matching qits-dns raises nothing and hostnames simply stop resolving.
 *
 * <p>Delivery is fire-and-forget across two hops, so assertions wait on a queue the fixture fills.
 */
class DnsDomainRegistrarTest {

  private record Received(String method, String path, String token, String body) {}

  private HttpServer server;
  private BlockingQueue<Received> puts;
  private final List<Received> all = new CopyOnWriteArrayList<>();

  /** What {@code GET /dns/api/zones} answers for the case under test. */
  private final AtomicReference<String> zonesBody = new AtomicReference<>("{\"zones\":[]}");

  @BeforeEach
  void startServer() throws IOException {
    puts = new ArrayBlockingQueue<>(4);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          Received request =
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().getFirst(DnsDomainRegistrar.TOKEN_HEADER),
                  new String(body, StandardCharsets.UTF_8));
          all.add(request);
          try {
            if ("GET".equals(request.method())) {
              byte[] zones = zonesBody.get().getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, zones.length);
              exchange.getResponseBody().write(zones);
            } else {
              puts.add(request);
              exchange.sendResponseHeaders(200, -1);
            }
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private DnsDomainRegistrar registrar(String token) {
    DnsDomainRegistrar registrar = new DnsDomainRegistrar();
    // Scheme, host and port only: the two paths are the registrar's, which is what is under test.
    registrar.dnsUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    registrar.configuredToken = Optional.ofNullable(token);
    registrar.objectMapper = new ObjectMapper();
    return registrar;
  }

  private Received awaitPut() throws InterruptedException {
    Received first = puts.poll(10, TimeUnit.SECONDS);
    if (first == null) {
      fail("no record write arrived within the deadline");
    }
    return first;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(String body) throws Exception {
    return new ObjectMapper().readValue(body, Map.class);
  }

  private static String zones(String... idAndFqdnPairs) {
    StringBuilder json = new StringBuilder("{\"zones\":[");
    for (int i = 0; i < idAndFqdnPairs.length; i += 2) {
      if (i > 0) {
        json.append(',');
      }
      // serial/createdAt/updatedAt are on the real wire and deliberately absent here: Zone ignores
      // unknown properties, and this pins that it needs only the two fields it declares.
      json.append("{\"id\":\"")
          .append(idAndFqdnPairs[i])
          .append("\",\"fqdn\":\"")
          .append(idAndFqdnPairs[i + 1])
          .append("\"}");
    }
    return json.append("]}").toString();
  }

  /** A name that IS the zone apex is written as {@code @}. */
  @Test
  void anExactZoneMatchIsWrittenAtTheApex() throws Exception {
    zonesBody.set(zones("zone-1", "qits.eu"));

    registrar(null).register("p-1", "qits", "qits.eu", ProjectDnsRecordType.A, "203.0.113.9");

    Received put = awaitPut();
    assertEquals("PUT", put.method());
    assertEquals("/dns/api/zones/zone-1/records", put.path());
    Map<String, Object> body = parse(put.body());
    assertEquals("@", body.get("name"));
    assertEquals("A", body.get("type"));
    assertEquals(List.of("203.0.113.9"), body.get("values"));
    assertNull(
        body.get("ttl"), "null ttl means 'follows the server default', which is not a value");
  }

  /** A name below the apex is written as the leftover label(s). */
  @Test
  void aSuffixMatchIsWrittenAsTheLeftoverLabel() throws Exception {
    zonesBody.set(zones("zone-1", "qits.eu"));

    registrar(null)
        .register("p-2", "shop", "app.shop.qits.eu", ProjectDnsRecordType.CNAME, "ingress.qits.eu");

    Map<String, Object> body = parse(awaitPut().body());
    assertEquals("app.shop", body.get("name"));
    assertEquals("CNAME", body.get("type"));
    assertEquals(List.of("ingress.qits.eu"), body.get("values"));
  }

  /** The longest matching zone wins, so a delegated child zone beats its parent. */
  @Test
  void theLongestMatchingZoneWins() throws Exception {
    zonesBody.set(zones("parent", "qits.eu", "child", "dev.qits.eu"));

    registrar(null)
        .register("p-3", "app", "app.dev.qits.eu", ProjectDnsRecordType.A, "203.0.113.9");

    Received put = awaitPut();
    assertEquals("/dns/api/zones/child/records", put.path());
    assertEquals("app", parse(put.body()).get("name"));
  }

  /**
   * The boundary rule, and the reason it is not a bare {@code endsWith}: {@code notqits.eu} ends
   * with the characters of {@code qits.eu} and is a different name entirely. Matching it would
   * repoint somebody else's hostname into our zone.
   */
  @Test
  void aNameThatOnlySharesCharactersWithAZoneIsNoMatch() {
    assertNull(
        DnsDomainRegistrar.resolveZone(
            "notqits.eu", List.of(new DnsDomainRegistrar.Zone("zone-1", "qits.eu"))));
    assertNull(
        DnsDomainRegistrar.resolveZone(
            "eu", List.of(new DnsDomainRegistrar.Zone("zone-1", "qits.eu"))));
    assertEquals(
        "zone-1",
        DnsDomainRegistrar.resolveZone(
                "a.qits.eu", List.of(new DnsDomainRegistrar.Zone("zone-1", "qits.eu")))
            .id());
  }

  /** And end to end: no PUT is made for a name whose only near-match is across a label boundary. */
  @Test
  void noZoneMatchMeansNoWriteAtAll() throws Exception {
    zonesBody.set(zones("zone-1", "qits.eu"));

    registrar(null).register("p-4", "other", "notqits.eu", ProjectDnsRecordType.A, "203.0.113.9");

    assertNull(
        puts.poll(2, TimeUnit.SECONDS),
        "a zone is delegated at a registrar, so an unmatched name must warn and stop");
    assertTrue(
        all.stream().allMatch(r -> "GET".equals(r.method())),
        "only the zone lookup reached the server");
  }

  @Test
  void anEmptyZoneListMeansNoWriteEither() throws Exception {
    zonesBody.set("{\"zones\":[]}");

    registrar(null).register("p-5", "any", "any.qits.eu", ProjectDnsRecordType.A, "203.0.113.9");

    assertNull(puts.poll(2, TimeUnit.SECONDS));
  }

  @Test
  void theTokenHeaderRidesAlongWhenConfigured() throws Exception {
    zonesBody.set(zones("zone-1", "qits.eu"));

    registrar("s3cret").register("p-6", "qits", "qits.eu", ProjectDnsRecordType.A, "203.0.113.9");

    assertEquals("s3cret", awaitPut().token());
  }

  /**
   * Blank is the shipped default and qits-dns' own open mode: no header at all, not an empty one.
   */
  @Test
  void aBlankTokenSendsNoHeader() throws Exception {
    zonesBody.set(zones("zone-1", "qits.eu"));

    registrar("   ").register("p-7", "qits", "qits.eu", ProjectDnsRecordType.A, "203.0.113.9");

    assertNull(awaitPut().token());
  }

  @Test
  void anUnreachableDnsNeitherBlocksNorThrows() {
    DnsDomainRegistrar registrar = new DnsDomainRegistrar();
    registrar.dnsUrl = "http://192.0.2.1:9";
    registrar.configuredToken = Optional.empty();
    registrar.objectMapper = new ObjectMapper();

    long before = System.nanoTime();
    registrar.register("p-8", "nowhere", "nowhere.qits.eu", ProjectDnsRecordType.A, "203.0.113.9");
    long elapsedMillis = (System.nanoTime() - before) / 1_000_000;

    assertTrue(
        elapsedMillis < 1_000,
        "fire-and-forget must not park the creating request (" + elapsedMillis + "ms)");
  }

  /** {@code @} for the apex, the leftover prefix otherwise — the arithmetic, without a server. */
  @Test
  void recordNameStripsExactlyTheZoneApex() {
    assertEquals("@", DnsDomainRegistrar.recordName("qits.eu", "qits.eu"));
    assertEquals("app", DnsDomainRegistrar.recordName("app.qits.eu", "qits.eu"));
    assertEquals("a.b", DnsDomainRegistrar.recordName("a.b.qits.eu", "qits.eu"));
  }
}
