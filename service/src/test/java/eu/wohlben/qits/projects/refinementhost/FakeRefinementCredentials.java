package eu.wohlben.qits.projects.refinementhost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The suite's {@link RefinementCredentials}: in-memory, off by default — the shipped configuration
 * commissions nothing, and every test that wants commissioning turns it on. Wins over
 * {@code idphost/IdpRefinementCredentials} because that bean is {@code @DefaultBean}.
 */
@ApplicationScoped
public class FakeRefinementCredentials implements RefinementCredentials {

  private boolean enabled;
  private final AtomicInteger minted = new AtomicInteger();
  private final Map<String, String> live = new LinkedHashMap<>();

  @Override
  public synchronized boolean enabled() {
    return enabled;
  }

  public synchronized void enable(boolean value) {
    enabled = value;
  }

  @Override
  public synchronized Commissioned commission(long refinementId) {
    String clientId = "dyn-refinement-" + refinementId + "-" + minted.incrementAndGet();
    String secret = "secret-" + clientId;
    live.put(clientId, Long.toString(refinementId));
    return new Commissioned(clientId, secret);
  }

  @Override
  public synchronized void decommission(String clientId) {
    live.remove(clientId);
  }

  @Override
  public synchronized List<Commission> listRefinementCommissions() {
    return live.entrySet().stream()
        .map(entry -> new Commission(entry.getKey(), entry.getValue()))
        .toList();
  }

  public synchronized int liveCount() {
    return live.size();
  }

  public synchronized void reset() {
    enabled = false;
    minted.set(0);
    live.clear();
  }
}
