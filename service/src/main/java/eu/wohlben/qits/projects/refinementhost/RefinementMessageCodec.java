package eu.wohlben.qits.projects.refinementhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * The host's bridge between a <b>workspace-daemon</b> {@link DaemonMessage} and its JSON text
 * frame — the refinement twin of {@code agenthost/DaemonMessageCodec}, over the other vendored
 * protocol module. The framework-free {@link DaemonCodec} does the field mapping to and from a
 * {@code Map}; this class only bolts on Jackson. The daemon binary does the symmetric job with a
 * Vert.x {@code JsonObject}.
 */
@ApplicationScoped
public class RefinementMessageCodec {

  @Inject ObjectMapper objectMapper;

  public String encode(DaemonMessage message) {
    try {
      return objectMapper.writeValueAsString(DaemonCodec.encode(message));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode a workspace-daemon message", e);
    }
  }

  @SuppressWarnings("unchecked")
  public DaemonMessage decode(String json) {
    try {
      return DaemonCodec.decode(objectMapper.readValue(json, Map.class));
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to decode a workspace-daemon message", e);
    }
  }
}
