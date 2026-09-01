-- IMPLEMENTED joins the stored statuses: the declared "shipped", entered through the transition
-- endpoint, which stamps every still-unimplemented feature and task in the same move. Added for
-- the epic with no features at all -- implemented straight from its description, nothing for the
-- feature derivation to fire on, stuck in IMPLEMENTATION with no way to say it had shipped.
alter table epic drop constraint ck_epic_status;
alter table epic add constraint ck_epic_status
    check (status in ('REFINING','IMPLEMENTATION','IMPLEMENTED','SUPERSEDED','ABANDONED'));
