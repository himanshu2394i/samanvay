# Journeys Consume Real Independent Departments — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Repoint the three journeys' INCOME/MARKS/PROPERTY/POLLUTION/BANK categories from the in-process mock backend to real independent department services over real transport (replace, not toggle).

**Architecture:** A connector routes real when its data source `base_host != mock.samanvay.test`; dev/demo config maps the source code to the real service URL. We add one `/bank` REST endpoint, then `V199` repoints the five journey connectors to new real-transport data sources and aligns their endpoint/template/mapping to the real services. Journey tests move to real transport against in-process fixtures.

**Tech Stack:** Spring Boot 4, Flyway (Postgres), Apache MINA sshd (SFTP), H2 (JDBC test), Jackson 3 (`tools.jackson`).

## Global Constraints

- Migrations forward-only; next is **V199** (latest on main is V198).
- Real data sources: `base_host` = a non-resolving `.example` host (real routing), never `mock.samanvay.test`.
- Credentials never in catalog/config — only `auth_config_ref` naming a SecretStore key.
- Commit trailer on every commit: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Only INCOME/MARKS/PROPERTY/POLLUTION/BANK move to real; CASTE/FIRE_NOC/LAND_RECORD/LAND_PARCEL/CROP stay mock.

---

## File Structure

- `simulators/.../department/SandboxDepartmentData.java` — add `Bank` record + `bank()` fixture.
- `simulators/.../department/SandboxDepartmentController.java` — add `GET /bank`.
- `simulators/.../department/SandboxDepartmentControllerTest.java` (or existing test) — cover `/bank`.
- `src/main/resources/db/migration/V199__journeys_real_sources.sql` — new real sources + repoint 5 connectors + fix pollution mapping.
- `src/main/resources/application-dev.yml`, `application-demo.yml` — map the 5 real sources.
- `src/test/java/com/samanvay/shared/test/RealDepartments.java` — shared in-process fixture harness (REST/SOAP/SFTP/JDBC) + `@DynamicPropertySource` helper.
- `src/test/java/.../JourneysRealResolutionTest.java` — asserts the 5 categories resolve REAL, siblings stay mock.
- `src/test/java/com/samanvay/DemoRehearsalIT.java` + journey ITs — use `RealDepartments`, assert real fixture values.

---

### Task 1: `/bank` endpoint on department-service

**Files:**
- Modify: `simulators/src/main/java/in/samanvay/simulators/department/SandboxDepartmentData.java`
- Modify: `simulators/src/main/java/in/samanvay/simulators/department/SandboxDepartmentController.java`
- Test: `simulators/src/test/java/in/samanvay/simulators/department/` (existing controller test, or new `BankEndpointTest`)

**Interfaces:**
- Produces: `GET /bank?dbtId=<id>` → JSON `{accountRef, ifscMasked, holderName, samanvay_simulator:true}`. Deterministic per `dbtId`.

- [ ] **Step 1: Write the failing test** (mirror the income test in the simulators module)

```java
@Test
void bank_returns_deterministic_account_for_a_dbt_id() {
    var resp = rest.getForEntity(base + "/bank?dbtId=DBT-1001", Map.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    assertThat(resp.getBody()).containsKey("accountRef");
    assertThat(resp.getBody().get("samanvay_simulator")).isEqualTo(true);
}
```

- [ ] **Step 2: Run it, expect FAIL** — `./mvnw -o -f simulators/pom.xml test -Dtest=*Bank*` (404/no endpoint).

- [ ] **Step 3: Implement.** In `SandboxDepartmentData` add:

```java
record Bank(String accountRef, String ifscMasked, String holderName) {}

private static final Map<String, Bank> BANK_FIXTURES = Map.of(
        "DBT-1001", new Bank("XXXXXX1234", "SBIN0XXX300", "Sandbox Holder"),
        "DBT-1002", new Bank("XXXXXX5678", "HDFC0XXX210", "Asha Patil"));

static Bank bank(String dbtId) {
    Bank known = BANK_FIXTURES.get(dbtId.toUpperCase(Locale.ROOT));
    if (known != null) return known;
    int h = Math.floorMod(dbtId.hashCode(), 1_000_000);
    return new Bank("XXXXXX" + String.format("%04d", h % 10000),
            "BANK0XXX" + (h % 900 + 100), HOLDERS.get(h % HOLDERS.size()));
}
```

In `SandboxDepartmentController` add (mirror `income`):

```java
@GetMapping(path = "/bank", produces = MediaType.APPLICATION_JSON_VALUE)
ResponseEntity<Map<String, Object>> bank(@RequestParam(name = "dbtId", required = false) String dbtId) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (dbtId == null || dbtId.isBlank()) {
        body.put("error", "dbtId is required");
        body.put("samanvay_simulator", true);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
    var b = SandboxDepartmentData.bank(dbtId.trim());
    body.put("accountRef", b.accountRef());
    body.put("ifscMasked", b.ifscMasked());
    body.put("holderName", b.holderName());
    body.put("samanvay_simulator", true);
    return ResponseEntity.ok(body);
}
```

- [ ] **Step 4: Run test, expect PASS.**
- [ ] **Step 5: Commit** — `feat(simulators): real /bank REST endpoint for the DBT department`.

---

### Task 2: Shared real-departments test fixture

**Files:**
- Create: `src/test/java/com/samanvay/shared/test/RealDepartments.java`

**Interfaces:**
- Produces: `RealDepartments.start()` → starts in-process REST/SOAP (`com.sun.net.httpserver.HttpServer`), SFTP (`org.apache.sshd.server.SshServer`), H2 (seeded `pcb_clearance`); exposes `register(DynamicPropertyRegistry)` that sets `samanvay.sources.department-service.urls.*`, `samanvay.sources.sftp.sources.dept-property-sftp.*` (host/port/host-key-sha256/mode=simulator), `samanvay.sources.jdbc.sources.dept-pollution-jdbc.*` (jdbc-url/mode=simulator), and the two SecretStore env-equivalent creds via a test `SecretStore` bean; `close()` stops all.
- Consumes: patterns from `SftpCsvRealTransportTest` (embedded sshd + fingerprint) and `JdbcRealTransportTest` (H2 setup).

- [ ] **Step 1: Write it** — one class starting the four servers with fixed fixture data matching the simulator shapes (income `annualIncome/holderName`, marks `percentage`, property CSV `propertyId,propertyRef,ward`, pollution `premise_id,clearance_status,holder`, bank `accountRef`). Capture the SFTP **RSA** fingerprint (MINA negotiates RSA) for the pin.
- [ ] **Step 2: Compile** — `./mvnw -o test-compile`. Expected PASS.
- [ ] **Step 3: Commit** — `test: shared in-process real-departments fixture harness`.

---

### Task 3: V199 migration — repoint the five connectors

**Files:**
- Create: `src/main/resources/db/migration/V199__journeys_real_sources.sql`
- Test: `src/test/java/com/samanvay/catalog/internal/service/JourneysRealResolutionTest.java`

**Interfaces:**
- Produces: after V199, connectors `rev-income@1`, `edu-marks@1`, `dbt-bank@1`, `muni-property@1`, `pcb-clearance@1` reference real data sources; sibling connectors unchanged.

- [ ] **Step 1: Write the failing resolution test** (extends `PostgresIntegrationTest`):

```java
@Test
void the_five_categories_resolve_real_and_siblings_stay_mock() {
    var real = jdbc.queryForList(
        "SELECT c.ref FROM catalog_connector c JOIN catalog_data_source ds ON ds.code=c.data_source_code "
      + "WHERE ds.base_host <> 'mock.samanvay.test' AND c.ref IN "
      + "('rev-income@1','edu-marks@1','dbt-bank@1','muni-property@1','pcb-clearance@1')", String.class);
    assertThat(real).hasSize(5);
    var mockSiblings = jdbc.queryForList(
        "SELECT c.ref FROM catalog_connector c JOIN catalog_data_source ds ON ds.code=c.data_source_code "
      + "WHERE ds.base_host='mock.samanvay.test' AND c.ref IN ('rev-caste@1','rev-land@1','fire-noc@1')", String.class);
    assertThat(mockSiblings).hasSize(3);
}
```

- [ ] **Step 2: Run it, expect FAIL** (currently all mock).
- [ ] **Step 3: Write V199:**

```sql
-- Repoint the five journey connectors that now have a real independent department service
-- (INCOME/MARKS/BANK via department-service, PROPERTY via department-sftp, POLLUTION via
-- department-db) from the mock backend to real-transport data sources. Additive sources +
-- surgical UPDATEs; sibling connectors on the shared mock sources are untouched. Forward-only; V199>V198.

INSERT INTO catalog_data_source (code, department_code, protocol, base_host, auth_type, auth_config_ref, health_status) VALUES
    ('dept-income-rest', 'REVENUE', 'REST', 'rest.revenue.samanvay.example', 'NONE', 'secret:none', 'UNKNOWN'),
    ('dept-marks-soap', 'EDUCATION', 'SOAP', 'soap.education.samanvay.example', 'NONE', 'secret:none', 'UNKNOWN'),
    ('dept-bank-rest', 'DBT', 'REST', 'rest.dbt.samanvay.example', 'NONE', 'secret:none', 'UNKNOWN'),
    ('dept-property-sftp', 'MUNICIPAL', 'SFTP_CSV', 'sftp.municipal.samanvay.example', 'PASSWORD', 'secret:dept-property-sftp', 'UNKNOWN'),
    ('dept-pollution-jdbc', 'POLLUTION', 'JDBC', 'jdbc.pollution.samanvay.example', 'PASSWORD', 'secret:dept-pollution-jdbc', 'UNKNOWN')
ON CONFLICT (code) DO NOTHING;

-- REST income: /income -> /v1/income (mapping annualIncome/holderName already matches the real service).
UPDATE catalog_connector SET data_source_code='dept-income-rest',
  capabilities='{"FETCH":{"endpoint":"/v1/income","mapping_ref":"map-rev-income@1","output_schema":"Credential/IncomeCertificate@1","error_paths":[]}}'
  WHERE ref='rev-income@1';

-- SOAP marks: /marks -> /marks/service, template carries <studentId> as the real service parses.
UPDATE catalog_connector SET data_source_code='dept-marks-soap',
  capabilities='{"FETCH":{"endpoint":"/marks/service","template":"<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><GetMarks><studentId>{{studentId}}</studentId></GetMarks></soap:Body></soap:Envelope>","mapping_ref":"map-edu-marks@1","output_schema":"Credential/Marks@1","error_paths":[]}}'
  WHERE ref='edu-marks@1';

-- REST bank: endpoint /bank unchanged, now the real department-service /bank.
UPDATE catalog_connector SET data_source_code='dept-bank-rest' WHERE ref='dbt-bank@1';

-- SFTP property: /property -> /outbound/property.csv.
UPDATE catalog_connector SET data_source_code='dept-property-sftp',
  capabilities='{"FETCH":{"endpoint":"/outbound/property.csv","mapping_ref":"map-muni-property@1","output_schema":"Credential/PropertyTax@1","error_paths":[]}}'
  WHERE ref='muni-property@1';

-- JDBC pollution: template already the real SELECT; only move the source.
UPDATE catalog_connector SET data_source_code='dept-pollution-jdbc' WHERE ref='pcb-clearance@1';

-- Real JDBC returns lowercased snake column keys, so the mapping source must be clearance_status
-- (mock returned camelCase clearanceStatus). Target stays clearanceStatus (schema/canonical unchanged).
UPDATE catalog_mapping SET rules='[{"source":"clearance_status","target":"clearanceStatus","transforms":[{"fn":"upper","args":[]}]}]'
  WHERE ref='map-pcb-clearance@1';
```

- [ ] **Step 4: Run resolution test, expect PASS.**
- [ ] **Step 5: Commit** — `feat(catalog): V199 repoint journey INCOME/MARKS/PROPERTY/POLLUTION/BANK to real sources`.

---

### Task 4: Dev + demo config for the real sources

**Files:**
- Modify: `src/main/resources/application-demo.yml`, `src/main/resources/application-dev.yml`

**Interfaces:**
- Consumes: source codes from Task 3.

- [ ] **Step 1: Add mappings** to both profiles (values mirror the existing sandbox entries):

```yaml
samanvay:
  sources:
    department-service:
      urls:
        dept-income-rest: ${SAMANVAY_DEPT_SERVICE_URL:http://localhost:8090}
        dept-marks-soap: ${SAMANVAY_DEPT_SERVICE_URL:http://localhost:8090}
        dept-bank-rest: ${SAMANVAY_DEPT_SERVICE_URL:http://localhost:8090}
    sftp:
      sources:
        dept-property-sftp:
          mode: sandbox
          host: ${SAMANVAY_SANDBOX_SFTP_HOST:localhost}
          port: ${SAMANVAY_SANDBOX_SFTP_PORT:2222}
          remote-path: /outbound/property.csv
          host-key-sha256: ${SAMANVAY_SANDBOX_SFTP_HOSTKEY:SHA256:REPLACE_WITH_SANDBOX_SERVER_FINGERPRINT}
    jdbc:
      sources:
        dept-pollution-jdbc:
          mode: sandbox
          jdbc-url: ${SAMANVAY_SANDBOX_JDBC_URL:jdbc:postgresql://localhost:5433/deptdb}
```

Credentials at runtime (env): `SAMANVAY_SECRET_SOURCE_DEPT_PROPERTY_SFTP_CREDENTIAL` = base64(`fixtureuser:fixturepass`), `SAMANVAY_SECRET_SOURCE_DEPT_POLLUTION_JDBC_CREDENTIAL` = base64(`pcb_ro:pcb_ro_demo`).

- [ ] **Step 2: YAML lint** (`python -c "import yaml,...`) and `./mvnw -o test-compile`. Expected PASS.
- [ ] **Step 3: Commit** — `feat(config): map the real journey sources to the department containers`.

---

### Task 5: Rewire journey ITs to real transport

**Files:**
- Modify: `src/test/java/com/samanvay/DemoRehearsalIT.java` and any journey IT that fetches the five categories (`FarmerSubsidyJourneyIT`, business-NOC / scholarship ITs).

**Interfaces:**
- Consumes: `RealDepartments` (Task 2).

- [ ] **Step 1:** In each affected IT, start `RealDepartments` and register its properties via `@DynamicPropertySource`; update any asserted income/marks/property/pollution/bank values to the fixture values from `RealDepartments`.
- [ ] **Step 2: Run** each IT — `./mvnw -o -Dit.test=DemoRehearsalIT ... verify` (Docker). Expected PASS.
- [ ] **Step 3: Commit** — `test: journeys run against real in-process department fixtures`.

---

## Self-Review

- **Spec coverage:** /bank (Task 1), V199 repoint + mapping fix (Task 3), config (Task 4), tests real (Tasks 2,5), resolution split test (Task 3). All spec sections covered.
- **Placeholder scan:** none — SQL, endpoints, mapping and config are concrete. The SFTP `host-key-sha256` placeholder is intentional (captured per-deployment; tests use `RealDepartments`' captured fingerprint via `@DynamicPropertySource`).
- **Type consistency:** connector refs, source codes, mapping refs consistent across tasks; `bank(dbtId)`/`Bank` used consistently; pollution source `clearance_status` matches the JDBC client's lowercasing.

## Post-implementation (outside this plan)
Deploy to the live EC2 (git pull the branch, rebuild, `docker compose up -d`, restart the app with the new source creds + fingerprint) and verify the three journeys fetch the five categories from the real containers.
