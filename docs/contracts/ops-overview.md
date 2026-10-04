# Staff overview - contract v1

What the staff console shows about the departments that were really onboarded (from their manifests), their documents, the mapping of each
document onto the central schema, and the journeys they run. Seeded demo and test rows (mock sources, sandbox connectors, the licence journey,
the stand-in scholarship journey, the schemas with no document category) are NOT part of this view.

## "Onboarded" means

A row created or adopted by manifest onboarding. Migration V211 adds `onboarded BOOLEAN NOT NULL DEFAULT FALSE` to `catalog_data_source`,
`catalog_connector` and `catalog_journey` (every existing row stays FALSE). `ManifestOnboardingService.onboard` sets it TRUE on every data source,
connector and journey it creates, and on a journey that already existed with a code the manifest declares (it is adopted, so the seeded
`FARMER_SUBSIDY` becomes visible once Agriculture is onboarded). A department is onboarded when `catalog_department.manifest_digest` is not null.

## `GET /api/ops/overview` (OFFICER, ADMIN)

```json
{
  "generatedAt": "2026-10-04T10:00:00Z",
  "departments": [
    {
      "code": "EDUCATION",
      "name": "State Board of Education",
      "pinnedKeyThumbprint": "JHRVn3yL...",
      "loginUrl": "https://education.example/login",
      "dataSources": [
        { "code": "education-soap", "protocol": "SOAP", "host": "education.example", "health": "GREEN", "healthDetail": null }
      ],
      "documents": [
        {
          "category": "MARKS",
          "title": "Marks",
          "connectorRef": "edu-marks@2",
          "connectorStatus": "PUBLISHED",
          "dataSourceCode": "education-soap",
          "sourceHealth": "GREEN",
          "lastTrial": { "at": "2026-10-04T09:30:00Z", "outcome": "SUCCESS" },
          "working": true,
          "centralSchemaRef": "Credential/Marks@1",
          "mappings": [
            { "source": "percentage", "target": "percentage", "required": true },
            { "source": "board", "target": "board", "required": false }
          ],
          "unmappedRequired": []
        }
      ],
      "journeys": [
        {
          "code": "EDUCATION_SCHOLARSHIP",
          "name": "Post-matric scholarship",
          "status": "DRAFT",
          "ready": true,
          "needs": [ { "category": "MARKS", "department": "EDUCATION", "working": true } ],
          "counts": { "running": 0, "completed": 0, "failed": 0, "last7Days": 0 }
        }
      ]
    }
  ]
}
```

- `departments`: onboarded departments only, ordered by code. `documents`: the categories its onboarded connectors serve (a connector that is
  still a DRAFT has `connectorStatus` DRAFT and `working` false). `connectorStatus` is DRAFT or PUBLISHED. `working` = published connector and
  source health not RED (the same rule as the journey status page).
- `mappings`: the connector's saved mapping rules (department field to central field), `required` taken from the central schema.
  `unmappedRequired`: required central fields no rule fills.
- `journeys`: onboarded journeys whose requester is this department. `ready`: every required category has a published connector (the rule
  used to allow publishing). `needs[].working`: that category's connector is published and its source is not RED. `counts` as on the journey
  status page. A journey is listed under its requester only; the journey page (`/api/ops/journeys/{code}`) has the full detail and log.
- Nothing secret and no citizen value.

## Central schema (existing endpoint, unchanged shape)

`GET /api/catalog/schema-details` still returns every schema. The console shows only those with a document category and, for each, which
onboarded department documents map onto it (from the overview).
