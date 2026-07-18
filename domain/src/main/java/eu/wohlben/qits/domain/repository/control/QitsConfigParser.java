package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.HealthCheckKind;
import eu.wohlben.qits.domain.daemon.entity.LogObserverKind;
import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.repository.control.QitsConfig.ActionDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfig.DaemonDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfig.FrameworkDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfig.HealthCheckDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfig.ObserverDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfig.RepositorySection;
import eu.wohlben.qits.domain.repository.control.QitsConfig.SourceDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfig.WebViewDecl;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads and parses a repository's committed {@code .qits-config.yml}. Mirrors {@link
 * GitSubmoduleParser} exactly: a pure, framework-light helper kept separate from the transactional
 * services so parsing is unit-testable without cloning. {@link #readConfig} pulls the file straight
 * from the bare origin (no checkout, no container) via {@link GitExecutor#showFile}; an absent file
 * (non-zero {@code git show}) or any read failure yields {@link QitsConfig#EMPTY} — the no-op
 * branch that keeps a config-free repository on the old path.
 *
 * <p>{@link #parse} is stricter: a structurally invalid file (bad YAML, wrong {@code version},
 * unknown enum) throws {@link QitsConfigException} so the reconciler can surface it as a
 * repository-level warning while keeping the last-good DB state. Per-entry <em>validation</em>
 * (regex, web-view, health-check ranges) is left to the daemon/action services downstream.
 */
@ApplicationScoped
public class QitsConfigParser {

  private static final Logger LOG = Logger.getLogger(QitsConfigParser.class);

  /** The committed file this feature reads, at the repository root. */
  public static final String CONFIG_PATH = ".qits-config.yml";

  @Inject GitExecutor git;

  /** A structural problem in {@code .qits-config.yml} — surfaced as a repository warning. */
  public static class QitsConfigException extends RuntimeException {
    public QitsConfigException(String message) {
      super(message);
    }

    public QitsConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The config declared in {@code branch}'s {@code .qits-config.yml} in the bare origin. A missing
   * file (non-zero {@code git show}) or any read/IO failure yields {@link QitsConfig#EMPTY}, never
   * an error — reading config must never fail a clone or sync. A structurally invalid file DOES
   * propagate ({@link QitsConfigException}), so the caller can record it as a warning.
   */
  public QitsConfig readConfig(File bareOrigin, String branch) {
    GitExecutor.ExecResult result;
    try {
      result = git.showFile(bareOrigin, branch, CONFIG_PATH);
    } catch (Exception e) {
      LOG.warnf(
          e, "Failed to read %s from %s@%s; treating as empty", CONFIG_PATH, bareOrigin, branch);
      return QitsConfig.EMPTY;
    }
    if (result.exitCode() != 0) {
      return QitsConfig.EMPTY;
    }
    return parse(result.output());
  }

  /**
   * Parses the YAML content into config records. Uses SnakeYAML's {@link SafeConstructor} (plain
   * maps/lists only — never instantiates arbitrary classes from repository content). Throws {@link
   * QitsConfigException} on any structural or type error.
   */
  public QitsConfig parse(String content) {
    if (content == null || content.isBlank()) {
      return QitsConfig.EMPTY;
    }
    Object root;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      root = yaml.load(content);
    } catch (Exception e) {
      throw new QitsConfigException("Invalid YAML: " + e.getMessage(), e);
    }
    if (root == null) {
      return QitsConfig.EMPTY;
    }
    Map<String, Object> map = asMap(root, "document root");

    Object version = map.get("version");
    if (!(version instanceof Number n) || n.intValue() != 1) {
      throw new QitsConfigException("Unsupported or missing 'version' (expected 1): " + version);
    }

    return new QitsConfig(
        repositorySection(map.get("repository")),
        frameworks(map.get("frameworks")),
        actions(map.get("actions")),
        daemons(map.get("daemons")));
  }

  private RepositorySection repositorySection(Object raw) {
    if (raw == null) {
      return null;
    }
    Map<String, Object> m = asMap(raw, "repository");
    return new RepositorySection(
        str(m, "main-branch"), enumOf(RepositoryArchetype.class, m.get("archetype"), "archetype"));
  }

  private List<FrameworkDecl> frameworks(Object raw) {
    List<FrameworkDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "frameworks")) {
      Map<String, Object> m = asMap(item, "frameworks[]");
      out.add(new FrameworkDecl(reqStr(m, "kind", "frameworks[]"), str(m, "root")));
    }
    return out;
  }

  private List<ActionDecl> actions(Object raw) {
    List<ActionDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "actions")) {
      Map<String, Object> m = asMap(item, "actions[]");
      out.add(
          new ActionDecl(
              reqStr(m, "name", "actions[]"),
              str(m, "description"),
              str(m, "execute"),
              str(m, "check"),
              bool(m, "interactive", false),
              strMap(m.get("environment"), "actions[].environment")));
    }
    return out;
  }

  private List<DaemonDecl> daemons(Object raw) {
    List<DaemonDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "daemons")) {
      Map<String, Object> m = asMap(item, "daemons[]");
      String name = reqStr(m, "name", "daemons[]");
      out.add(
          new DaemonDecl(
              name,
              str(m, "description"),
              str(m, "start"),
              str(m, "ready-pattern"),
              boolOrNull(m.get("otel")),
              boolOrNull(m.get("auto-start")),
              enumOf(RestartPolicy.class, m.get("restart-policy"), "restart-policy"),
              intOrNull(m.get("max-restarts"), "max-restarts"),
              str(m, "stop-signal"),
              strMap(m.get("environment"), "daemons[].environment"),
              webView(m.get("web-view")),
              observers(m.get("observers")),
              sources(m.get("sources")),
              healthChecks(m.get("health-checks"))));
    }
    return out;
  }

  private WebViewDecl webView(Object raw) {
    if (raw == null) {
      return null;
    }
    Map<String, Object> m = asMap(raw, "web-view");
    return new WebViewDecl(
        intOrNull(m.get("port"), "web-view.port"), str(m, "entry-path"), str(m, "base-path"));
  }

  private List<ObserverDecl> observers(Object raw) {
    List<ObserverDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "observers")) {
      Map<String, Object> m = asMap(item, "observers[]");
      out.add(
          new ObserverDecl(
              enumOf(LogObserverKind.class, m.get("kind"), "observers[].kind"),
              str(m, "pattern"),
              enumOf(DaemonEventSeverity.class, m.get("severity"), "observers[].severity")));
    }
    return out;
  }

  private List<SourceDecl> sources(Object raw) {
    List<SourceDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "sources")) {
      Map<String, Object> m = asMap(item, "sources[]");
      out.add(new SourceDecl(reqStr(m, "path", "sources[]"), str(m, "label")));
    }
    return out;
  }

  private List<HealthCheckDecl> healthChecks(Object raw) {
    List<HealthCheckDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "health-checks")) {
      Map<String, Object> m = asMap(item, "health-checks[]");
      out.add(
          new HealthCheckDecl(
              reqStr(m, "name", "health-checks[]"),
              enumOf(HealthCheckKind.class, m.get("kind"), "health-checks[].kind"),
              intOrNull(m.get("port"), "health-checks[].port"),
              str(m, "path"),
              str(m, "expect-status"),
              str(m, "command"),
              longOrNull(m.get("interval-ms"), "interval-ms"),
              longOrNull(m.get("timeout-ms"), "timeout-ms"),
              intOrNull(m.get("healthy-threshold"), "healthy-threshold"),
              intOrNull(m.get("unhealthy-threshold"), "unhealthy-threshold"),
              longOrNull(m.get("initial-delay-ms"), "initial-delay-ms")));
    }
    return out;
  }

  // ---- typed extraction helpers --------------------------------------------------------------

  private Map<String, Object> asMap(Object raw, String where) {
    if (raw instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    throw new QitsConfigException("Expected a mapping at " + where + ", got: " + typeOf(raw));
  }

  private List<Object> asList(Object raw, String where) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof List<?> l) {
      return new ArrayList<>(l);
    }
    throw new QitsConfigException("Expected a list at " + where + ", got: " + typeOf(raw));
  }

  private static String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null) {
      return null;
    }
    String s = String.valueOf(v);
    return s.isBlank() ? null : s;
  }

  private String reqStr(Map<String, Object> m, String key, String where) {
    String v = str(m, key);
    if (v == null) {
      throw new QitsConfigException("Missing required '" + key + "' at " + where);
    }
    return v;
  }

  private boolean bool(Map<String, Object> m, String key, boolean fallback) {
    Boolean v = boolOrNull(m.get(key));
    return v == null ? fallback : v;
  }

  private Boolean boolOrNull(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Boolean b) {
      return b;
    }
    throw new QitsConfigException("Expected a boolean, got: " + typeOf(v));
  }

  private Integer intOrNull(Object v, String where) {
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.intValue();
    }
    throw new QitsConfigException("Expected an integer at " + where + ", got: " + typeOf(v));
  }

  private Long longOrNull(Object v, String where) {
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.longValue();
    }
    throw new QitsConfigException("Expected a number at " + where + ", got: " + typeOf(v));
  }

  private Map<String, String> strMap(Object raw, String where) {
    if (raw == null) {
      return Map.of();
    }
    Map<String, Object> m = asMap(raw, where);
    Map<String, String> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
    return out;
  }

  private <E extends Enum<E>> E enumOf(Class<E> type, Object v, String where) {
    if (v == null) {
      return null;
    }
    String name = String.valueOf(v).trim().toUpperCase().replace('-', '_');
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException e) {
      throw new QitsConfigException(
          "Invalid value '" + v + "' at " + where + " (expected one of " + values(type) + ")");
    }
  }

  private static <E extends Enum<E>> String values(Class<E> type) {
    List<String> names = new ArrayList<>();
    for (E c : type.getEnumConstants()) {
      names.add(c.name());
    }
    return String.join(", ", names);
  }

  private static String typeOf(Object v) {
    return v == null ? "null" : v.getClass().getSimpleName();
  }
}
