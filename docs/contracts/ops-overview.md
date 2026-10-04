# Staff overview - contract v1

What the staff console shows about the departments that were really onboarded (from their manifests), their documents, the mapping of each
document onto the central schema, and the journeys they run. Seeded demo and test rows (mock sources, sandbox connectors, the licence journey,
the stand-in scholarship journey, the schemas with no document category) are NOT part of this view.

## "Onboarded" means

A row created or adopted by manifest onboarding. Migration V211 adds `onboarded BOOLEAN NOT NULL DEFAULT FALSE` to `catalog_data_source`,
`catalog_connector` and `catalog_journey` (every existing row stays FALSE). `ManifestOnboardingService.onboard` sets it TRUE on every data source,
connector and journey it creates, and on rows it ADOPTS: a data source that already exists with the same code and the same department, and a
journey that already existed with a code the manifest declares (the seeded `FARMER_SUBSIDY` becomes visible once Agriculture is onboarded).
It never adopts another department's rows: a data source code that belongs to another department, or a journey whose requester is another
department, refuses the onboarding (and a journey's requester must be the manifest's own department). A department is onboarded when
`catalog_department.manifest_digest` is not null.

**No backfill (V213 was deliberately not written).** Rows onboarded before V211 stay FALSE. A backfill needs either the time the department's
manifest was onboarded (not stored: only the digest is) or the manifest host (not stored either); guessing from "the host is not a seeded
one" would flag hand-made rows. To make such a department visible, onboard it again: the same data sources and journeys are adopted and the
new connector versions are created flagged.

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
          "unmappedRequired": [],
          "pendingUpdateRef": null
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

- `departments`: onboarded departments only, ordered by code. `documents`: per category, the onboarded connector that is SERVING: the highest
  PUBLISHED version (the one resolution uses), or the highest DRAFT when none is published yet (`connectorStatus` DRAFT, `working` false).
  A DRAFT newer than the serving version does not replace it: its ref is `pendingUpdateRef` (null when there is none), a "pending update".
  `connectorStatus` is DRAFT or PUBLISHED.
- **`working`** (documents, journey `needs`, and the journey page `categories`): the connector is PUBLISHED and its data source's health is
  GREEN or AMBER. `sourceHealth` is always exposed. A source nobody has probed is `UNKNOWN` and is **not working**; RED is not working.
  SFTP and JDBC sources cannot be probed, so they are always `UNKNOWN`; for those only, `working` is true when the connector's last durable
  trial (`lastTrial`, table `connector_trial`) has outcome `SUCCESS`. A REST or SOAP source that is UNKNOWN is not working whatever its trial
  said: probe it.
- `mappings`: the connector's saved mapping rules (department field to central field), `required` taken from the central schema.
  `unmappedRequired`: required central fields no rule fills.
- `journeys`: onboarded journeys whose requester is this department. `ready`: every required category has a PUBLISHED connector **that
  manifest onboarding created for the named provider department**; a seeded or hand-made connector of that department never makes an onboarded
  journey ready or working. `needs[].working`: that onboarded connector is published and working by the rule above. `counts` as on the journey
  status page. A journey is listed under its requester only; the journey page (`/api/ops/journeys/{code}`) has the full detail and log.
- Nothing secret and no citizen value.

## Central schema (existing endpoint, unchanged shape)

`GET /api/catalog/schema-details` still returns every schema. The console shows only those with a document category and, for each, which
onboarded department documents map onto it (from the overview).
