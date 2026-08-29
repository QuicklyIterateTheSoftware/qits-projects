package eu.wohlben.qits.projects.stories.support;

/**
 * The one launched process, addressed the way each of its surfaces is addressed — and named the way
 * a diagram names it.
 *
 * <p>Everything qits-projects serves to a machine hangs off <b>one segment</b>. {@code
 * quarkus.rest.path=/projects/api} is the JSON API; {@code
 * quarkus.http.non-application-root-path=/projects/q} is what Quarkus itself serves, and the
 * framework's shipped RestAssured tap skips any path carrying a {@code /q/} segment — which is
 * exactly right here, so no story class overrides the predicate. The segment is not decoration: the
 * platform edge path-routes every application's segment on every host, so dropping it is the
 * difference between this service being reachable and not.
 *
 * <p>The <b>port is random</b> — failsafe launches the artifact with {@code
 * quarkus.http.test-port=0} — so nothing here is a constant except the paths. RestAssured is
 * configured with the port by the Quarkus integration-test extension, so an API call needs no base
 * url at all; only {@link StoryPlatform}'s tap-invisible fixture client builds one, and it reads
 * {@code RestAssured.port} for exactly that reason.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-projects";

  /** {@code /projects/api} — {@code quarkus.rest.path}. A resource's {@code @Path} is relative. */
  public static final String API_PATH = "/projects/api";

  /** The projects collection: {@code GET} is the overview, {@code POST} creates one. */
  public static final String PROJECTS_PATH = API_PATH + "/projects";

  /** The flat repository catalogue — qits-ci's trigger catalogue, and the platform inventory. */
  public static final String REPOSITORIES_PATH = API_PATH + "/repositories";

  private StoryTarget() {}

  /** One project: {@code /projects/api/projects/<id>}. */
  public static String projectPath(String projectId) {
    return PROJECTS_PATH + "/" + projectId;
  }

  /** The project's components, plus its wrapper's manifest as the UI reads it. */
  public static String projectRepositoriesPath(String projectId) {
    return projectPath(projectId) + "/repositories";
  }

  /** The bootstrap's door: register a repository the git host already serves. {@code qits:system}. */
  public static String adoptPath(String projectId) {
    return projectRepositoriesPath(projectId) + "/adopt";
  }

  /** qits-githost's own read: a project-scoped repository name becomes a storage id. */
  public static String byNamePath(String projectId, String repoName) {
    return projectRepositoriesPath(projectId) + "/by-name/" + repoName;
  }

  /** One repository by id — qits-workspaces' lookup and the workspaces detail screen. */
  public static String repositoryPath(String repoId) {
    return REPOSITORIES_PATH + "/" + repoId;
  }

  /** The project's epics: {@code GET} lists them, {@code POST} proposes one. */
  public static String projectEpicsPath(String projectId) {
    return projectPath(projectId) + "/epics";
  }

  /** One epic by id. */
  public static String epicPath(String epicId) {
    return API_PATH + "/epics/" + epicId;
  }

  /** The epic's features: {@code GET} lists them, {@code POST} adds one. */
  public static String epicFeaturesPath(String epicId) {
    return epicPath(epicId) + "/features";
  }

  /** The only door that moves an epic's status — the scope freeze and the two terminal moves. */
  public static String epicTransitionPath(String epicId) {
    return epicPath(epicId) + "/transition";
  }

  /** The epic subtree's whole change history, newest first — it outlives the rows it describes. */
  public static String epicAuditPath(String epicId) {
    return epicPath(epicId) + "/audit";
  }

  /** One feature by id. */
  public static String featurePath(String featureId) {
    return API_PATH + "/features/" + featureId;
  }

  /** The feature's tasks: {@code GET} lists them, {@code POST} adds one. */
  public static String featureTasksPath(String featureId) {
    return featurePath(featureId) + "/tasks";
  }

  /** One task by id — where the implemented marker is set. */
  public static String taskPath(String taskId) {
    return API_PATH + "/tasks/" + taskId;
  }
}
