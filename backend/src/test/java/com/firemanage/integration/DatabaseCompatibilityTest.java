package com.firemanage.integration;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.firemanage.FireManageApplication;
import com.firemanage.common.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCompatibilityTest {
    @TempDir
    Path temp;

    @Test
    void startsWithOracleStyleSchemaAndKeepsTheExistingApiContract() throws Exception {
        Path schema = h2Schema();
        Path drillFiles = temp.resolve("drill-files");
        try (ServletWebServerApplicationContext app = (ServletWebServerApplicationContext)
                new SpringApplicationBuilder(FireManageApplication.class).run(
                        "--server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:fire_manage;MODE=Oracle",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.datasource.hikari.connection-init-sql=",
                        "--spring.sql.init.mode=always",
                        "--spring.sql.init.schema-locations=" + schema.toUri(),
                        "--fire.drill-directory=" + drillFiles,
                        "--fire.bootstrap-password=fire-test-password",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=warn")) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM fm_workflow_config", Integer.class));

            Client client = new Client(app.getWebServer().getPort());
            ObjectMapper json = app.getBean(ObjectMapper.class);
            client.refreshCsrf();
            JsonNode account = client.request(HttpMethod.POST, "/api/auth/login",
                    json.createObjectNode().put("username", "admin").put("password", "fire-test-password"), JsonNode.class);
            assertEquals("admin", account.path("username").asText());
            assertTrue(account.path("menus").size() > 0);
            client.refreshCsrf();

            ObjectNode profile = json.createObjectNode().put("displayName", "测试管理员");
            profile.putObject("profile").put("phone", "0731");
            JsonNode updated = client.request(HttpMethod.PUT, "/api/auth/profile", profile, JsonNode.class);
            assertEquals("0731", updated.path("profile").path("phone").asText());

            ObjectNode report = json.createObjectNode();
            HttpClientErrorException invalid = assertThrows(HttpClientErrorException.class,
                    () -> client.request(HttpMethod.POST, "/api/approvals", report, JsonNode.class));
            assertEquals(400, invalid.getRawStatusCode());
            assertTrue(json.readTree(invalid.getResponseBodyAsByteArray()).path("fieldErrors").has("title"));

            String description = String.join("", Collections.nCopies(1400, "消防"));
            report.put("title", "第一条").put("location", "车站").put("description", description);
            long firstId = client.request(HttpMethod.POST, "/api/approvals", report, JsonNode.class).path("id").asLong();
            report.put("title", "第二条").put("description", "第二条详情");
            long secondId = client.request(HttpMethod.POST, "/api/approvals", report, JsonNode.class).path("id").asLong();
            assertTrue(firstId > 0 && secondId > firstId);

            JsonNode page = client.request(HttpMethod.GET, "/api/approvals?page=2&size=1", null, JsonNode.class);
            assertEquals(2, page.path("total").asInt());
            assertEquals(1, page.path("items").size());
            JsonNode approval = page.path("items").get(0).path("approval");
            assertEquals(firstId, approval.path("id").asLong());
            assertEquals(description, approval.path("description").asText());
            assertTrue(approval.path("createdAt").asText().contains("T"));

            List<Long> ids = new ArrayList<>(Collections.nCopies(1000, -1L));
            ids.add(secondId);
            JdbcClient.IdList selection = new JdbcClient.IdList(ids);
            assertEquals(1L, app.getBean(JdbcClient.class).sql("SELECT COUNT(*) FROM fm_approval WHERE " + selection.matches("id"))
                    .params(selection.parameters()).query(Long.class).single());

            verifyWorkflow(client, json, report);
            verifyDevices(client, json, jdbc);
            verifyDashboard(client, json, jdbc);
            verifyDrillAttachments(client, json, jdbc, drillFiles);
        }
    }

    private Path h2Schema() throws Exception {
        String schema;
        try (InputStream input = new ClassPathResource("schema.sql").getInputStream()) {
            schema = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        // H2 has case-insensitive strings but does not support Oracle function-based indexes.
        String compatible = schema.replace("code VARCHAR2(64 CHAR) NOT NULL", "code VARCHAR_IGNORECASE(64) NOT NULL UNIQUE")
                .replace("CREATE UNIQUE INDEX uq_fire_device_code_ci ON fm_fire_device (LOWER(code));", "");
        return Files.write(temp.resolve("schema.sql"), compatible.getBytes(StandardCharsets.UTF_8));
    }

    private static void verifyWorkflow(Client client, ObjectMapper json, ObjectNode report) {
        String flow = "/api/approval-flows/WORKBENCH";
        JsonNode initial = client.request(HttpMethod.GET, flow, null, JsonNode.class);
        ObjectNode publish = json.createObjectNode().put("revision", initial.path("revision").asInt());
        publish.set("draft", initial.path("example"));
        JsonNode published = client.request(HttpMethod.POST, flow + "/publish", publish, JsonNode.class);
        assertEquals("assign", published.path("published").path("definition").path("startKey").asText());
        long id = client.request(HttpMethod.POST, "/api/approvals", report, JsonNode.class).path("id").asLong();
        JsonNode detail = client.request(HttpMethod.GET, "/api/approvals/" + id, null, JsonNode.class);
        assertEquals("IN_WORKFLOW", detail.path("approval").path("status").asText());
    }

    private static void verifyDevices(Client client, ObjectMapper json, JdbcTemplate jdbc) throws Exception {
        long unitId = jdbc.queryForObject("SELECT id FROM fm_fire_unit WHERE code = 'CHANGCHE'", Long.class);
        ObjectNode device = json.createObjectNode().put("unitId", unitId).put("code", "FM-01")
                .put("category", "EXTINGUISHER").put("itemName", "灭火器").put("storageLocation", "库房")
                .put("manufacturedOn", "2026-01-01");
        client.request(HttpMethod.POST, "/api/fire/devices", device, JsonNode.class);
        device.put("code", "fm-01");
        HttpClientErrorException duplicate = assertThrows(HttpClientErrorException.class,
                () -> client.request(HttpMethod.POST, "/api/fire/devices", device, JsonNode.class));
        assertEquals(409, duplicate.getRawStatusCode());
        JsonNode devices = client.request(HttpMethod.GET, "/api/fire/devices?keyword=fm-01", null, JsonNode.class);
        assertEquals(1, devices.path("total").asInt());
        assertEquals("2026-01-01", devices.path("items").get(0).path("device").path("manufacturedOn").asText());

        byte[] template = client.request(HttpMethod.GET, "/api/fire/devices/template", null, byte[].class);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            assertTrue(workbook.getNumberOfSheets() > 0);
        }
    }

    private static void verifyDashboard(Client client, ObjectMapper json, JdbcTemplate jdbc) {
        long unitId = jdbc.queryForObject("SELECT id FROM fm_fire_unit WHERE code = 'CHANGCHE'", Long.class);
        ObjectNode hazard = json.createObjectNode().put("unitId", unitId).put("title", "大屏测试隐患")
                .put("location", "候车厅").put("description", "消防器材需复核");
        client.request(HttpMethod.POST, "/api/fire/hazards", hazard, JsonNode.class);

        JsonNode dashboard = client.request(HttpMethod.GET, "/api/fire/dashboard", null, JsonNode.class);
        assertEquals(1, dashboard.path("summary").path("deviceTotal").asInt());
        assertEquals(1, dashboard.path("summary").path("hazardOpen").asInt());
        assertEquals(7, dashboard.path("categories").size());
        assertEquals(6, dashboard.path("trends").size());
        assertTrue(dashboard.path("unitStatuses").size() >= 1);
        assertTrue(dashboard.path("recentActivities").size() >= 2);

        JsonNode scoped = client.request(HttpMethod.GET, "/api/fire/dashboard?unitId=" + unitId, null, JsonNode.class);
        assertEquals(unitId, scoped.path("selectedUnitId").asLong());
        assertEquals(1, scoped.path("summary").path("deviceTotal").asInt());
    }

    private static void verifyDrillAttachments(Client client, ObjectMapper json, JdbcTemplate jdbc, Path directory) {
        long unitId = jdbc.queryForObject("SELECT id FROM fm_fire_unit WHERE code = 'CHANGCHE'", Long.class);
        ObjectNode drill = json.createObjectNode().put("unitId", unitId).put("title", "附件验证演练")
                .put("drilledAt", "2026-01-03T09:30:00").put("participantCount", 4);
        long drillId = client.request(HttpMethod.POST, "/api/fire/drills", drill, JsonNode.class).path("id").asLong();

        byte[] content = "drill summary".getBytes(StandardCharsets.UTF_8);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("kind", "SUMMARY");
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "summary.txt";
            }
        });

        String collection = "/api/fire/drills/" + drillId + "/files";
        long fileId = client.request(HttpMethod.POST, collection, form, JsonNode.class).path("id").asLong();
        String key = jdbc.queryForObject("SELECT storage_key FROM fm_fire_drill_file WHERE id = ?", String.class, fileId);
        assertTrue(Files.isRegularFile(directory.resolve(key)));
        String attachment = collection + "/" + fileId;
        assertArrayEquals(content, client.request(HttpMethod.GET, attachment, null, byte[].class));

        client.request(HttpMethod.DELETE, attachment, null, Void.class);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fm_fire_drill_file WHERE id = ?", Integer.class, fileId));
        assertFalse(Files.exists(directory.resolve(key)));
    }

    private static final class Client {
        private final RestTemplate http = new RestTemplate();
        private final String base;
        private String cookie;
        private String csrfHeader;
        private String csrfToken;

        private Client(int port) {
            base = "http://127.0.0.1:" + port;
        }

        void refreshCsrf() {
            JsonNode csrf = request(HttpMethod.GET, "/api/auth/csrf", null, JsonNode.class);
            csrfHeader = csrf.path("header").asText();
            csrfToken = csrf.path("token").asText();
        }

        <T> T request(HttpMethod method, String path, Object body, Class<T> reply) {
            HttpHeaders headers = new HttpHeaders();
            if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
            if (csrfHeader != null) headers.set(csrfHeader, csrfToken);
            if (body != null) {
                headers.setContentType(body instanceof MultiValueMap ? MediaType.MULTIPART_FORM_DATA : MediaType.APPLICATION_JSON);
            }
            ResponseEntity<T> response = http.exchange(base + path, method, new HttpEntity<>(body, headers), reply);
            for (String value : response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
                if (value.startsWith("FIRESESSION=")) cookie = value.split(";", 2)[0];
            }
            return response.getBody();
        }
    }
}
