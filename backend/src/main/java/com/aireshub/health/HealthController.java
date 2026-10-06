package com.aireshub.health;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 进程能应答,并且连得上业务库。Redis / Kafka 挂了不影响这里。 */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("select 1");
            return ResponseEntity.ok(Map.of("status", "ok", "database", "up"));
        } catch (Exception ex) {
            return ResponseEntity.status(503).body(Map.of("status", "down", "database", "down"));
        }
    }
}
