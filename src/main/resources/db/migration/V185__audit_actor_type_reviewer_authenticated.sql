-- V185__audit_actor_type_reviewer_authenticated.sql
-- Widens the actor_type CHECK for two more actor kinds:
--   REVIEWER      - a staff user acting with the REVIEWER role (identity
--                   candidate confirm/reject), previously recorded as OFFICER
--   AUTHENTICATED - a refused /api call whose token was valid (trusted realm,
--                   audience, client) but carried no Samanvay role
--
-- Same rules as V180: only the constraint changes, no existing row is
-- updated or backfilled (the hash chain covers row content; old OFFICER
-- rows for reviewer actions stay as written).
ALTER TABLE audit.audit_entry DROP CONSTRAINT audit_entry_actor_type_check;
ALTER TABLE audit.audit_entry ADD CONSTRAINT audit_entry_actor_type_check
    CHECK (actor_type IN ('CITIZEN','OFFICER','REVIEWER','SYSTEM','ADMIN','DEPARTMENT','ANONYMOUS','AUTHENTICATED'));
