package eu.wohlben.qits.projects.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * The one dns record a project's domain resolves through — {@code {domain, type, value}}, stored
 * 1:1 as the payload qits-dns is handed and nothing more.
 *
 * <p><b>A declared placeholder.</b> Domain configuration belongs to a service that does not exist
 * yet; until it does, a project needs somewhere to say "this is my hostname" and this embeddable is
 * that somewhere (see {@code main-environment-plan.md} §1). When the dedicated service arrives,
 * this class, its three columns and the {@link Project#dns} field are <b>deleted</b> rather than
 * migrated, so nothing else may grow a dependency on them: read it through {@code ProjectDto}, hand
 * it to the {@code ProjectDomainRegistrar} port, and go no further.
 *
 * <p>All three columns are nullable and the field on {@link Project} is read as {@code null} when
 * they all are — Hibernate's default for an {@link Embedded} whose every column is null. That is
 * load-bearing in two directions: rows predating this feature have no record, and the self-seed may
 * run with no domain configured at all (both are "this project registers no domain", not "this
 * project has an empty record"). {@code ProjectService} is what refuses a half-filled one.
 *
 * <p>{@code value} is required for every type, {@code CNAME} included: a CNAME without a target is
 * not a record, so there is nothing to default it to — the caller supplies the target or supplies
 * no record.
 */
@Embeddable
public class ProjectDnsRecord {

  /**
   * The fully-qualified name being configured ({@code qits.eu}, {@code app.qits.eu}) — the whole
   * name, never zone-relative. Splitting it against a zone apex is the registrar port's job,
   * because which zones exist is a fact only qits-dns holds.
   */
  @Column(name = "dns_domain")
  public String domain;

  /**
   * {@code STRING}, not the JPA default {@code ORDINAL}: this column is read by a human debugging a
   * deployment's hostname, and an ordinal would silently re-point every stored record if the enum
   * ever gained a constant in the middle.
   */
  @Column(name = "dns_type")
  @Enumerated(EnumType.STRING)
  public ProjectDnsRecordType type;

  /**
   * The address or CNAME target. Named {@code dns_value} in the schema and not {@code value}: H2
   * 2.x reserves the bare word, which is the same rock qits-dns' own record table had to route
   * around.
   */
  @Column(name = "dns_value")
  public String value;

  public ProjectDnsRecord() {}

  public ProjectDnsRecord(String domain, ProjectDnsRecordType type, String value) {
    this.domain = domain;
    this.type = type;
    this.value = value;
  }
}
