INSERT INTO registry_category_policy (data_category, sensitivity, discovery_policy) VALUES
    ('LAND_PARCEL', 'RESTRICTED', 'VISIBLE'),
    ('CROP_RECORD', 'RESTRICTED', 'VISIBLE');

INSERT INTO registry_clearance (requester_id, sensitivity) VALUES
    ('AGRICULTURE', 'PUBLIC'),
    ('AGRICULTURE', 'RESTRICTED'),
    ('AGRICULTURE', 'SENSITIVE');
