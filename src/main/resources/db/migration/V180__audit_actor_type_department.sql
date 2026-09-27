-- V180__audit_actor_type_department.sql
-- Widens the actor_type CHECK for two new actor kinds:
--   DEPARTMENT - a department integration's client-credentials token
--   ANONYMOUS  - a refused /api call that presented no valid token
--
-- Numbered above every existing migration (not in audit's V1-V19 block):
-- Flyway runs with outOfOrder=false, so a new V3 would be rejected by any
-- database that has already applied V160. See the PR/LLD note.
--
-- Only the constraint changes. No existing row is updated, rewritten or
-- backfilled: the hash chain covers row content, and every existing value
-- is still valid under the wider CHECK (Postgres re-validates, it does not
-- rewrite). Runs as samanvay_migrate (table owner); samanvay_app's
-- SELECT/INSERT-only grant is untouched.
ALTER TABLE audit.audit_entry DROP CONSTRAINT audit_entry_actor_type_check;
ALTER TABLE audit.audit_entry ADD CONSTRAINT audit_entry_actor_type_check
    CHECK (actor_type IN ('CITIZEN','OFFICER','SYSTEM','ADMIN','DEPARTMENT','ANONYMOUS'));
