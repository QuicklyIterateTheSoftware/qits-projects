package eu.wohlben.qits.domain.project.entity;

import eu.wohlben.qits.domain.repository.entity.Repository;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.List;

@Entity
public class Project extends PanacheEntityBase {

    @Id
    public String id;

    @Column(nullable = false)
    public String name;

    public String description;

    @OneToMany(mappedBy = "project")
    public List<Repository> repositories;
}
