package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.control.FeatureService;
import eu.wohlben.qits.epics.control.TaskService;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns an epic, feature or task id into the project whose live channel has to hear about it, and
 * fires the hint. Every epics controller announces its mutations through this one bean, so the
 * routes stay free of the walk up the tree.
 *
 * <p>Resolve <em>before</em> a delete: once the row is gone there is no way back to its project.
 */
@ApplicationScoped
class EpicChangeHints {

  @Inject ProjectChangePublisher publisher;

  @Inject EpicService epicService;

  @Inject FeatureService featureService;

  @Inject TaskService taskService;

  /** Announce that the project's epic tree changed. */
  void fire(String projectId) {
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
  }

  String projectOfEpic(String epicId) {
    return epicService.get(epicId).projectId;
  }

  String projectOfFeature(String featureId) {
    return projectOfEpic(featureService.get(featureId).epicId);
  }

  String projectOfTask(String taskId) {
    return projectOfFeature(taskService.get(taskId).featureId);
  }
}
