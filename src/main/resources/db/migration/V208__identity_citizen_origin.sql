-- How a citizen record came to be. DEPT_HOME: made by a department portal sign in (the only kind that may be merged into another).
ALTER TABLE identity_citizen ADD COLUMN origin VARCHAR(20);
