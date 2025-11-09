package nl2sql.nl2sql.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl2sql.nl2sql.dto.Dialect;
import nl2sql.nl2sql.dto.TranslateRequest;
import nl2sql.nl2sql.dto.TranslateResponse;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class TranslateService {

    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=AIzaSyBIxjSGVjcS5BX2LAM-pxQHuc0SW-jTLaU";

    private final SchemaService schemaService;
    private final ObjectMapper mapper = new ObjectMapper();

    public TranslateService(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    public TranslateResponse translate(TranslateRequest request) {
        RestTemplate restTemplate = new RestTemplate();
        long start = System.currentTimeMillis();

        Dialect dialect = request.dialect();
        String dialectName = (dialect != null) ? dialect.name() : "MYSQL";
        String text = request.text() != null ? request.text() : "";
        String queryType = request.queryType() != null ? request.queryType().toUpperCase() : "SELECT";

        // 🔹 Fetch schema summary (for schema-aware query generation)
        String jdbcUrl = schemaService.toJdbcUrl(request.host(), request.port(), request.database());
        String schemaSummary = schemaService.fetchSchemaSummary(jdbcUrl, request.username(), request.password(), 50, 50);

        // 🔹 Build prompt depending on optimize mode
        String prompt;
        if (request.optimize()) {
            // Optimization mode
            prompt = """
                You are a SQL optimization engine.
                Return a STRICT JSON object with ALL of the following fields ALWAYS present:
                  "optimized_sql": string,
                  "suggestions": array of strings (at least 2 suggestions),
                  "indexes": array of strings (at least 1 index recommendation),
                  "complexity": string (Big-O notation),
                  "cost": string (Low/Medium/High),
                  "explanation": string
                  
                SQL Query:
                %s
                """.formatted(text);
        } else {
            // Natural language → SQL generation with schema
            prompt = String.format("""
                You are an expert at generating correct %s SQL queries for MySQL.
                Below is the **actual schema** of the target database (tables and columns). Use this schema — do NOT invent tables or columns.
                If the schema could not be fetched, you will see a SCHEMA-ERROR message; in that case, respond with a short error message asking the user to provide valid DB credentials or describe the table.

                === SCHEMA START ===
                %s
                === SCHEMA END ===

                Now generate a syntactically correct %s query for the user's intent.
                Return STRICT JSON ONLY:
                {
                  "sql": "...",
                  "explanation": "..."
                }

                User intent: %s
                """, queryType, schemaSummary, queryType, text);
        }

        // 🔹 Prepare request for Gemini
        Map<String, Object> body = new HashMap<>();
        body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        body.put("generationConfig", Map.of("responseMimeType", "application/json"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response;
        try {
            response = restTemplate.postForEntity(GEMINI_API_URL, entity, Map.class);
        } catch (Exception e) {
            return new TranslateResponse(
                    "Gemini API call failed: " + e.getMessage(),
                    dialectName,
                    "gemini-2.0-flash",
                    System.currentTimeMillis() - start,
                    "Request failed",
                    null, null, null, null, null, null
            );
        }

        String rawJson = "";
        try {
            var candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
            var content = (Map<String, Object>) candidates.get(0).get("content");
            var parts = (List<Map<String, Object>>) content.get("parts");
            rawJson = parts.get(0).get("text").toString().trim();
        } catch (Exception e) {
            return new TranslateResponse(
                    "Gemini response error: " + e.getMessage(),
                    dialectName,
                    "gemini-2.0-flash",
                    System.currentTimeMillis() - start,
                    "Invalid response format",
                    null, null, null, null, null, null
            );
        }

        // 🔹 Clean up Markdown code fences
        rawJson = rawJson.replace("```json", "").replace("```", "").trim();

        JsonNode root;
        try {
            root = mapper.readTree(rawJson);
        } catch (Exception e) {
            return new TranslateResponse(
                    "JSON Parse Error: " + e.getMessage() + "\nRAW: " + rawJson,
                    dialectName,
                    "gemini-2.0-flash",
                    System.currentTimeMillis() - start,
                    "Failed to parse model output",
                    null, null, null, null, null, null
            );
        }

        // 🔹 Parse output
        long latency = System.currentTimeMillis() - start;

        if (!request.optimize()) {
            // Normal translation mode
            return new TranslateResponse(
                    root.path("sql").asText(""),
                    dialectName,
                    "gemini-2.0-flash",
                    latency,
                    null,
                    root.path("explanation").asText(""),
                    null,          // optimizedSql
                    null,          // suggestions
                    null,          // indexes
                    null,          // complexity
                    null           // cost
            );
        }

        // 🔹 Optimization mode: extract fields
        List<String> suggestions = new ArrayList<>();
        List<String> indexes = new ArrayList<>();
        if (root.has("suggestions") && root.get("suggestions").isArray())
            root.get("suggestions").forEach(node -> suggestions.add(node.asText()));

        if (root.has("indexes") && root.get("indexes").isArray())
            root.get("indexes").forEach(node -> indexes.add(node.asText()));

        return new TranslateResponse(
                root.path("sql").asText(""),
                dialectName,
                "gemini-2.0-flash",
                latency,
                null,
                root.path("explanation").asText(""),
                root.path("optimized_sql").asText(""),
                suggestions,
                indexes,
                root.path("complexity").asText("Unknown"),
                root.path("cost").asText("Unknown")
        );
    }
}
