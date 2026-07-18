-- The last .qits-config.yml ingestion problem for a repository (docs/features/
-- 2026-07-18_qits-config-in-repo-configuration.md). Config ingestion "degrades loudly, never
-- blocks": a parse or per-entry validation failure keeps the last-good rows and records its message
-- here instead of failing the clone/sync. NULL means the file is absent or was ingested cleanly.
--
-- Config-origin actions/daemons need no origin column: they are namespaced by the reserved
-- '@qits-config' name suffix (the write API rejects that suffix in user input), so origin is
-- derivable from the name alone.

alter table Repository add column config_warning varchar(4000);
