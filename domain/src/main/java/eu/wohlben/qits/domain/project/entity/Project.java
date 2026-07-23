package eu.wohlben.qits.domain.project.entity;

import eu.wohlben.qits.domain.featureflow.entity.FeatureFlowConfiguration;
import eu.wohlben.qits.domain.repository.entity.Repository;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.List;

/**
 * The aggregate root: <b>one application, organized as a polyrepository</b>. Its {@link
 * #repositories} are the parts of that single app — microservices, shared libraries, extracted
 * fixtures — curated together by one maintainer, <b>not</b> an aggregation of arbitrary third-party
 * repos. This framing is load-bearing when reasoning about the submodule/workspace code (name
 * collisions within a project are the maintainer's own choice; {@code origin} is a backup, not an
 * authority): see the package doc ({@link eu.wohlben.qits.domain.repository}) and {@code
 * docs/guides/project-model.md}.
 */
@Entity
public class Project extends PanacheEntityBase {

  @Id public String id;

  @Column(nullable = false)
  public String name;

  public String description;

  @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
  public List<Repository> repositories;

  @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
  public List<FeatureFlowConfiguration> featureFlowConfigurations;
}
