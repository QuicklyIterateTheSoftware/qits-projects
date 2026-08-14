package eu.wohlben.qits.projects.idphost;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A qits-idp that never leaves this JVM: the JDK's own {@link HttpServer} on an ephemeral port,
 * answering whatever a test scripts and recording exactly what arrived.
 *
 * <p>A deliberate sibling of {@code containershost/StubContainersServer}, and deliberately as dumb:
 * it routes nothing and holds no client store. What the real routes answer is proved in
 * qits-platform-idp's own suite; this exists for what a real service will not produce on demand — a
 * 401 mid-cutover, a connection nothing accepts — and for reading the request off the wire.
 */
final class StubIdpServer implements AutoCloseable {

  /** One request, as the stub saw it. */
  record Received(String method, String path, String authorization, String body) {}

  private record Scripted(int status, String body) {}

  private final HttpServer server;
  private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
  private final Deque<Scripted> answers = new ArrayDeque<>();

  StubIdpServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getRawPath(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  new String(body, StandardCharsets.UTF_8)));
          Scripted answer;
          synchronized (answers) {
            answer = answers.isEmpty() ? new Scripted(500, "{}") : answers.removeFirst();
          }
          byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(answer.status(), out.length == 0 ? -1 : out.length);
          if (out.length > 0) {
            exchange.getResponseBody().write(out);
          }
          exchange.close();
        });
    server.start();
  }

  /** The base a caller is pointed at — an idp's {@code …/idp}. */
  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/idp";
  }

  StubIdpServer answering(int status, String body) {
    synchronized (answers) {
      answers.addLast(new Scripted(status, body));
    }
    return this;
  }

  List<Received> received() {
    return List.copyOf(received);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
