package in.samanvay.departments.kit;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The journeys this department really offers, read from its own {@code journeys.json}. This is the ONE source: the manifest the
 * department publishes and the screens the portal shows are both made from it, so the manifest cannot promise a journey the
 * portal does not have.
 */
public final class JourneyCatalog {

    public record Category(String category, String department) {}

    public record Field(String name, String label, String type, boolean required, List<String> options) {}

    public record Journey(
            String code,
            String name,
            String description,
            String referencePrefix,
            int slaHours,
            String consentPurpose,
            List<Category> requiredCategories,
            List<Field> form) {}

    private record File(List<Journey> journeys) {}

    private final String deptCode;
    private final List<Journey> journeys;

    public JourneyCatalog(String deptCode, InputStream json) {
        this.deptCode = deptCode;
        try (json) {
            File f = JsonMapper.builder().build().readValue(json, new TypeReference<File>() {});
            this.journeys = f.journeys() == null ? List.of() : List.copyOf(f.journeys());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read journeys.json", e);
        }
        this.journeys.forEach(j -> {
            if (j.code() == null || j.code().isBlank() || j.consentPurpose() == null || j.requiredCategories() == null) {
                throw new IllegalStateException("journeys.json: every journey needs a code, a consentPurpose and requiredCategories");
            }
        });
    }

    public List<Journey> all() {
        return journeys;
    }

    public Optional<Journey> byCode(String code) {
        return journeys.stream().filter(j -> j.code().equals(code)).findFirst();
    }

    /** The {@code journeys} array of this department's manifest, each with the address of its portal. */
    public List<Map<String, Object>> manifestJourneys(String publicBaseUrl) {
        return journeys.stream().map(j -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", j.code());
            m.put("name", j.name());
            m.put("description", j.description());
            m.put("referencePrefix", j.referencePrefix());
            m.put("slaHours", j.slaHours());
            m.put("consentPurpose", j.consentPurpose());
            m.put("requester", deptCode);
            m.put("requiredCategories", j.requiredCategories());
            m.put("portalUrl", publicBaseUrl + "/portal/#/journeys/" + j.code());
            return m;
        }).toList();
    }
}
