-- Contact address for address-based channels (EMAIL: the e-mail address; SMS: the phone number).
-- Nullable: IN_APP needs none. The address is owned by the subscription itself - notifications has no
-- dependency on identity for contact details.
ALTER TABLE notification_subscription ADD COLUMN address VARCHAR(200);
