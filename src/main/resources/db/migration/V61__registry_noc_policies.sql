INSERT INTO registry_category_policy (data_category, sensitivity, discovery_policy) VALUES
    ('PROPERTY', 'RESTRICTED', 'VISIBLE'),
    ('FIRE_NOC', 'RESTRICTED', 'VISIBLE'),
    ('POLLUTION_CLEARANCE', 'RESTRICTED', 'VISIBLE'),
    ('LAND_RECORD', 'RESTRICTED', 'VISIBLE');

INSERT INTO registry_clearance (requester_id, sensitivity) VALUES
    ('INDUSTRY', 'PUBLIC'),
    ('INDUSTRY', 'RESTRICTED'),
    ('INDUSTRY', 'SENSITIVE');
