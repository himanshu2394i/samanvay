-- Orchestration's own record of where an application stands, so the officer approval step can
-- decide VERIFIED -> APPROVED atomically without reading another module's table at runtime.
-- Until now the status only travelled on ApplicationStateChanged (tracking projects it).
-- APPROVED is already an allowed tracking_application.status (V140); nothing to add there.
--
-- Additive and forward-only. Existing rows are seeded from the tracking projection so
-- applications verified before this migration can be approved; rows tracking does not know keep
-- the SUBMITTED default (they are not approvable until a fetch outcome is recorded).

ALTER TABLE orchestration_instance
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED'
        CONSTRAINT orchestration_instance_status_check CHECK (status IN
            ('SUBMITTED','PARTIALLY_VERIFIED','VERIFIED','APPROVED','REJECTED','CLOSED'));

UPDATE orchestration_instance o
   SET status = t.status
  FROM tracking_application t
 WHERE t.id = o.id;
