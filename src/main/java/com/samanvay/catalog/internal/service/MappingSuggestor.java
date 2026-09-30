package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.MappingSuggestion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proposes source→target field mappings for the onboarding importer. Two passes, both advisory:
 *
 * <ul>
 *   <li><b>lexical</b> — normalise, then substring / Levenshtein similarity (catches spelling
 *       variants like {@code annual_income} ↔ {@code annualIncome});</li>
 *   <li><b>semantic</b> — a curated domain concept dictionary, so abbreviations and synonyms that are
 *       lexically distant but mean the same thing still match (e.g. {@code dob} ↔ {@code dateOfBirth},
 *       {@code acct_no} ↔ {@code accountNumber}, {@code mobile} ↔ {@code phone}).</li>
 * </ul>
 *
 * Per target the higher-scoring pass wins (lexical wins ties, being the more transparent). Every
 * suggestion is propose-only: {@code approved=false} and nothing in the catalog is mutated — a person
 * still ticks each row before it becomes a rule.
 */
class MappingSuggestor {

    private static final double THRESHOLD = 0.45;
    private static final double SEMANTIC_SCORE = 0.92;

    List<MappingSuggestion> suggest(List<String> sources, List<String> targets) {
        List<MappingSuggestion> out = new ArrayList<>();
        for (String target : targets) {
            String bestSource = null;
            String bestRationale = "lexical";
            double best = 0;
            for (String source : sources) {
                double lex = score(source, target);
                double sem = semanticScore(source, target);
                double combined = sem > lex ? sem : lex;
                String rationale = sem > lex ? "semantic" : "lexical";
                if (combined > best) {
                    best = combined;
                    bestSource = source;
                    bestRationale = rationale;
                }
            }
            if (bestSource != null && best >= THRESHOLD) {
                out.add(new MappingSuggestion(bestSource, target, round(best), bestRationale, false));
            }
        }
        out.sort(Comparator.comparingDouble(MappingSuggestion::confidence).reversed());
        return List.copyOf(out);
    }

    /** 0.92 when both field names resolve to the same domain concept, else 0. */
    private static double semanticScore(String source, String target) {
        String cs = CONCEPTS.get(norm(source));
        String ct = CONCEPTS.get(norm(target));
        return cs != null && cs.equals(ct) ? SEMANTIC_SCORE : 0.0;
    }

    private static double score(String source, String target) {
        String a = norm(source);
        String b = norm(target);
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.contains(b) || b.contains(a)) {
            return 0.85;
        }
        int dist = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 0 : 1.0 - ((double) dist / max);
    }

    static String norm(String raw) {
        String spaced = raw.replace('_', ' ').replace('-', ' ');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spaced.length(); i++) {
            char c = spaced.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(spaced.charAt(i - 1))) {
                sb.append(' ');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Curated, deterministic dictionary of domain concepts for the departments Samanvay onboards
     * (identity, finance/DBT, address, education). Keyed by the normalised alias so it composes with
     * {@link #norm}. Intentionally small and easy to extend as new departments arrive; no ML/LLM and
     * no external service (HLD §1.5). Whole-field aliases only — no generic-token expansion — so it
     * stays precise and auditable.
     */
    private static final Map<String, String> CONCEPTS = buildConcepts();

    private static Map<String, String> buildConcepts() {
        Map<String, String> m = new HashMap<>();
        register(m, "DATE_OF_BIRTH", "dob", "dateOfBirth", "birthDate", "dateBirth", "birthDay");
        register(m, "FULL_NAME", "name", "fullName", "holderName", "applicantName", "beneficiaryName", "accountHolderName");
        register(m, "ANNUAL_INCOME", "income", "annualIncome", "yearlyIncome", "incomeAmount");
        register(m, "AMOUNT", "amount", "amt", "value");
        register(
                m,
                "ACCOUNT_NUMBER",
                "accountNumber",
                "accountNo",
                "acctNo",
                "acNo",
                "accNo",
                "bankAccountNumber",
                "accountNumberMasked");
        register(m, "IFSC", "ifsc", "ifscCode", "branchCode");
        register(m, "BANK_NAME", "bank", "bankName");
        register(m, "PHONE", "phone", "mobile", "mobileNo", "mobileNumber", "contactNumber", "phoneNumber", "contact");
        register(m, "EMAIL", "email", "emailId", "mail", "emailAddress");
        register(m, "ADDRESS", "address", "addr", "residence", "residentialAddress");
        register(m, "POSTAL_CODE", "pincode", "pin", "postalCode", "zip", "zipCode");
        register(m, "GENDER", "gender", "sex");
        register(m, "CASTE_CATEGORY", "caste", "category", "socialCategory", "casteCategory");
        register(m, "MARKS", "marks", "score", "percentage", "grade", "cgpa", "marksObtained");
        register(m, "ACADEMIC_YEAR", "academicYear", "yearOfStudy");
        // Maharashtra revenue / DBT / land-record vocabulary (zilla-taluka-gram, survey/gat numbers).
        register(m, "DISTRICT", "district", "districtName", "zilla", "zillaName");
        register(m, "TALUKA", "taluka", "tehsil", "taluk", "talukaName");
        register(m, "VILLAGE", "village", "villageName", "gram", "gramName");
        register(m, "STATE", "state", "stateName");
        register(m, "FATHER_NAME", "fatherName", "fathersName", "guardianName");
        register(m, "SURVEY_NUMBER", "surveyNumber", "surveyNo", "gatNumber", "gatNo", "khasraNumber");
        register(m, "LAND_AREA", "landArea", "areaHectares", "areaInHectares", "cultivableArea");
        register(m, "CROP", "crop", "cropName", "cropType");
        register(m, "RATION_CARD", "rationCard", "rationCardNumber", "rationCardNo");
        return Map.copyOf(m);
    }

    private static void register(Map<String, String> m, String concept, String... aliases) {
        for (String alias : aliases) {
            m.put(norm(alias), concept);
        }
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
