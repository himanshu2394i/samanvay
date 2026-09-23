package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.IssuedDocuments;
import com.samanvay.connector.api.IssuedField;
import com.samanvay.connector.api.IssuedRecord;
import com.samanvay.connector.api.LockerDocument;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
class IssuedDocumentsImpl implements IssuedDocuments {

    private static final String SANDBOX_NOTE =
            "DigiLocker partner sandbox on this laptop — not live DigiLocker, not Aadhaar login.";

    private final MockDepartmentBackend mocks;

    IssuedDocumentsImpl(MockDepartmentBackend mocks) {
        this.mocks = mocks;
    }

    @Override
    public List<LockerDocument> lockerForDepartment(String departmentCode) {
        String code = departmentCode == null ? "" : departmentCode.toUpperCase();
        return switch (code) {
            case "REVENUE" -> List.of(
                    doc("income", "Income certificate", "Revenue / Tahsildar", "Aaple Sarkar", "https://aaplesarkar.mahaonline.gov.in/"),
                    doc("caste", "Caste certificate", "Revenue / Tahsildar", "Aaple Sarkar", "https://aaplesarkar.mahaonline.gov.in/"),
                    doc("712", "7/12 extract (Record of Rights)", "Settlement Commissioner", "Mahabhulekh", "https://bhulekh.mahabhumi.gov.in/"));
            case "EDUCATION" -> List.of(
                    doc("marks", "HSC / equivalent marks", "Maharashtra State Board", "MSBSHSE", "https://mahahsscboard.in/"));
            case "DBT" -> List.of(
                    doc("bank", "Bank account for DBT", "Direct Benefit Transfer", "MahaDBT", "https://mahadbt.maharashtra.gov.in/"));
            case "AGRICULTURE" -> List.of(
                    doc("crop", "Crop / khate record", "Department of Agriculture", "MahaDBT Farmer", "https://mahadbt.maharashtra.gov.in/"));
            case "MUNICIPAL" -> List.of(
                    doc("property", "Property / assessment record", "Municipal Corporation", "MahaVastu / BPMS", "https://mahavastu.maharashtra.gov.in/"));
            case "FIRE" -> List.of(
                    doc("fire", "Fire NOC", "Maharashtra Fire & Emergency Services", "e-Fire approval", "https://mahafireservice.gov.in/e-fire.php"));
            case "POLLUTION" -> List.of(
                    doc("pcb", "Pollution consent", "Maharashtra Pollution Control Board", "MPCB via MAITRI", "https://maitri.maharashtra.gov.in/"));
            default -> List.of();
        };
    }

    @Override
    public IssuedRecord preview(String stepCode, String departmentCode, String stepStatus) {
        Meta meta = meta(stepCode);
        boolean completed = stepStatus != null && stepStatus.startsWith("COMPLETE");
        List<IssuedField> fields = completed ? fields(meta.endpoint) : List.of();
        String fetchStatus = completed ? "RECEIVED" : (stepStatus == null ? "WAITING" : stepStatus);
        return new IssuedRecord(
                stepCode,
                departmentCode,
                meta.issuer,
                meta.system,
                meta.url,
                meta.title,
                meta.kind,
                fields,
                false,
                fetchStatus);
    }

    private List<IssuedField> fields(String endpoint) {
        JsonNode n = mocks.fetch(endpoint, Map.of());
        return switch (endpoint) {
            case "/income" -> List.of(
                    field("Holder", text(n, "holderName")),
                    field("Annual income", text(n, "annualIncomeDisplay")),
                    field("District", text(n, "district")),
                    field("Issuer", text(n, "issuerOffice")));
            case "/caste" -> List.of(
                    field("Category", text(n, "casteCategory")),
                    field("Certificate", text(n, "certificateNo")));
            case "/marks" -> List.of(
                    field("Percentage", text(n, "percentage")),
                    field("Exam", text(n, "exam")));
            case "/bank" -> List.of(
                    field("Account (masked)", text(n, "accountRef")),
                    field("IFSC (masked)", text(n, "ifscMasked")));
            case "/land" -> List.of(
                    field("Survey no. / GAT", text(n, "surveyNo")),
                    field("Village", text(n, "village")),
                    field("Area", text(n, "area")));
            case "/crop" -> List.of(
                    field("Season", text(n, "season")),
                    field("Crop", text(n, "crop")));
            case "/fire" -> List.of(
                    field("NOC status", text(n, "nocStatusDisplay")),
                    field("NOC no.", text(n, "nocNo")));
            case "/pollution" -> List.of(
                    field("Clearance", text(n, "clearanceStatusDisplay")),
                    field("Category", text(n, "pcbCategory")));
            case "/property" -> List.of(
                    field("Property ID", text(n, "propertyId")),
                    field("Ward", text(n, "ward")));
            default -> List.of(field("Status", text(n, "ok")));
        };
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "—" : v.asString();
    }

    private LockerDocument doc(String id, String title, String issuer, String system, String url) {
        return new LockerDocument(id, title, issuer, system, url, SANDBOX_NOTE);
    }

    private static IssuedField field(String label, String value) {
        return new IssuedField(label, value);
    }

    private static Meta meta(String stepCode) {
        String code = stepCode == null ? "" : stepCode;
        return switch (code) {
            case "INCOME_CERTIFICATE" -> new Meta(
                    "/income",
                    "Income certificate",
                    "certificate",
                    "Revenue / Tahsildar",
                    "Aaple Sarkar",
                    "https://aaplesarkar.mahaonline.gov.in/");
            case "CASTE_CERTIFICATE" -> new Meta(
                    "/caste",
                    "Caste certificate",
                    "certificate",
                    "Revenue / Tahsildar",
                    "Aaple Sarkar",
                    "https://aaplesarkar.mahaonline.gov.in/");
            case "MARKS" -> new Meta(
                    "/marks",
                    "HSC / equivalent marks",
                    "marksheet",
                    "Maharashtra State Board",
                    "MSBSHSE",
                    "https://mahahsscboard.in/");
            case "BANK_ACCOUNT" -> new Meta(
                    "/bank",
                    "Bank account for DBT",
                    "bank",
                    "Direct Benefit Transfer",
                    "MahaDBT",
                    "https://mahadbt.maharashtra.gov.in/");
            case "LAND_PARCEL", "LAND_RECORD" -> new Meta(
                    "/land",
                    "7/12 extract (Record of Rights)",
                    "land",
                    "Settlement Commissioner",
                    "Mahabhulekh",
                    "https://bhulekh.mahabhumi.gov.in/");
            case "CROP_RECORD" -> new Meta(
                    "/crop",
                    "Crop / khate record",
                    "crop",
                    "Department of Agriculture",
                    "MahaDBT Farmer",
                    "https://mahadbt.maharashtra.gov.in/");
            case "FIRE_NOC" -> new Meta(
                    "/fire",
                    "Fire NOC",
                    "noc",
                    "Maharashtra Fire & Emergency Services",
                    "e-Fire approval",
                    "https://mahafireservice.gov.in/e-fire.php");
            case "POLLUTION_CLEARANCE" -> new Meta(
                    "/pollution",
                    "Pollution consent",
                    "consent",
                    "Maharashtra Pollution Control Board",
                    "MPCB via MAITRI",
                    "https://maitri.maharashtra.gov.in/");
            case "PROPERTY" -> new Meta(
                    "/property",
                    "Property / assessment record",
                    "property",
                    "Municipal Corporation",
                    "MahaVastu / BPMS",
                    "https://mahavastu.maharashtra.gov.in/");
            default -> new Meta("/unknown", code.isBlank() ? "Department record" : code, "record", "Department", "Department system", "");
        };
    }

    private record Meta(String endpoint, String title, String kind, String issuer, String system, String url) {}
}
