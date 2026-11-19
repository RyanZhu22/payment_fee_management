package com.omsweb.fees;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditTrail {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditTrail(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
            ? authentication.getName()
            : "SYSTEM";
    }

    public void record(String action, String entityType, String entityId, Object before, Object after) {
        recordAs(actor(), action, entityType, entityId, before, after);
    }

    void recordAs(String actor, String action, String entityType, String entityId, Object before, Object after) {
        jdbc.sql("""
            INSERT INTO audit_events (actor, action, entity_type, entity_id, before_value, after_value)
            VALUES (:actor, :action, :entityType, :entityId, CAST(:before AS jsonb), CAST(:after AS jsonb))
            """)
            .param("actor", actor)
            .param("action", action)
            .param("entityType", entityType)
            .param("entityId", entityId)
            .param("before", before == null ? null : json.writeValueAsString(before))
            .param("after", after == null ? null : json.writeValueAsString(after))
            .update();
    }

    List<Event> recent() {
        return jdbc.sql("""
            SELECT id, actor, action, entity_type, entity_id, before_value, after_value, created_at
            FROM audit_events ORDER BY id DESC LIMIT 100
            """)
            .query((row, number) -> new Event(
                row.getLong("id"), row.getString("actor"), row.getString("action"),
                row.getString("entity_type"), row.getString("entity_id"),
                parse(row.getString("before_value")), parse(row.getString("after_value")),
                row.getObject("created_at", OffsetDateTime.class)))
            .list();
    }

    private JsonNode parse(String value) {
        return value == null ? null : json.readTree(value);
    }

    record Event(long id, String actor, String action, String entityType, String entityId,
                 JsonNode before, JsonNode after, OffsetDateTime createdAt) {}
}
