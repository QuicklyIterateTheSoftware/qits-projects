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
