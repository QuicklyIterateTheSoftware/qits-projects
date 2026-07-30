-- The project's own dns record: {domain, type, value}, embedded on Project (ProjectDnsRecord).
--
-- A DECLARED PLACEHOLDER (main-environment-plan.md §1). Domain configuration belongs to a service
-- that does not exist yet; when it arrives these three columns are DROPPED rather than migrated, so
-- nothing outside ProjectDnsRecord and the ProjectDomainRegistrar port may read them.
--
-- All three nullable and NO BACKFILL, deliberately. A project without a record registers no domain,
-- and that is a real state twice over: rows created before this migration, and a self-seed run
-- without qits.startup-seed.dns-* configured. Hibernate reads an @Embedded whose every column is
-- null as a null field, so both arrive at every reader as "no domain" rather than as an empty
-- record — which only holds while nothing here defaults a column.
--
-- The API requires the object (POST /projects/api/projects carries @NotNull dns); the schema does
-- not. That asymmetry is the point of the nullability and not a gap in it.
alter table Project add column dns_domain varchar(255);

-- STRING-valued, matching @Enumerated(EnumType.STRING) and qits-dns' own spelling of the same three
-- types. No check constraint: the enum is the projects context's, the authoritative type rules are
-- qits-dns', and a third copy in DDL would be the one nobody remembers to widen.
alter table Project add column dns_type varchar(255);

-- dns_value, not `value`: H2 2.x reserves the bare word — the same rock qits-dns' record table had
-- to route around by naming its own column `rdata`.
alter table Project add column dns_value varchar(255);
