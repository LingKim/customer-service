package cn.net.susan.customer.config;

import cn.net.susan.customer.mapper.SchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动自检 + 自动补结构。
 *
 * <p>customer_db 是"增量脚本"演进的：新功能上线时会给旧库补表补列。脚本忘了执行，
 * 代码一跑到相关查询就抛 {@code column "xxx" does not exist}，报错发生在深层调用栈里，
 * 很难一眼看出是"少跑了一个脚本"。</p>
 *
 * <p>所以启动时做两件事：</p>
 * <ol>
 *   <li><b>自检</b>：把代码依赖的表 / 列核一遍；</li>
 *   <li><b>补结构</b>：缺什么就执行对应的增量脚本（脚本都是可重复执行的，且只在缺的时候才跑）。</li>
 * </ol>
 *
 * <p>自动补结构需显式启用：{@code --yunti.schema.auto-migrate=true}</p>
 */
@Component
public class SchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaGuard.class);

    /** 可执行脚本的固定顺序：先建表后补列，和 scripts/migrate-customer-db.sh 保持一致 */
    private static final List<String> SCRIPT_ORDER = List.of(
            "customer_db_security.sql",
            "customer_db_collab.sql",
            "customer_db_delivery.sql",
            "customer_db_qa.sql",
            "customer_db_routing.sql",
            "customer_db_realtime_qa.sql",
            "customer_db_qa_source.sql",
            "customer_db_qa_timeout.sql"
    );

    /** 代码依赖的表 → 来源脚本 */
    private static final Map<String, String> REQUIRED_TABLES = new LinkedHashMap<>();

    /** 代码依赖的列（表.列）→ 来源脚本 */
    private static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>();

    static {
        REQUIRED_TABLES.put("agent_status", "customer_db_routing.sql");
        REQUIRED_TABLES.put("qa_rule", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_task", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_review", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_alert", "customer_db_realtime_qa.sql");

        REQUIRED_COLUMNS.put("channel.allowed_origins", "customer_db_security.sql");
        REQUIRED_COLUMNS.put("session.last_msg_seq", "customer_db_delivery.sql");
        REQUIRED_COLUMNS.put("session.skill_group_id", "customer_db_routing.sql");
        REQUIRED_COLUMNS.put("session_message.client_msg_no", "customer_db_delivery.sql");
        REQUIRED_COLUMNS.put("session_message.seq", "customer_db_delivery.sql");
        REQUIRED_COLUMNS.put("session_message.visible_to", "customer_db_collab.sql");
        REQUIRED_COLUMNS.put("skill_group.overflow_after_seconds", "customer_db_routing.sql");
        REQUIRED_COLUMNS.put("qa_rule.is_realtime", "customer_db_realtime_qa.sql");
        REQUIRED_COLUMNS.put("qa_rule.hit_keywords", "customer_db_realtime_qa.sql");
        REQUIRED_COLUMNS.put("qa_rule.severity", "customer_db_realtime_qa.sql");
        REQUIRED_COLUMNS.put("qa_rule.timeout_seconds", "customer_db_qa_timeout.sql");
    }

    private final SchemaMapper schemaMapper;
    private final DataSource dataSource;
    private final boolean autoMigrate;

    public SchemaGuard(
            SchemaMapper schemaMapper,
            DataSource dataSource,
            @Value("${yunti.schema.auto-migrate:false}") boolean autoMigrate
    ) {
        this.schemaMapper = schemaMapper;
        this.dataSource = dataSource;
        this.autoMigrate = autoMigrate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            checkAndRepair();
        } catch (Exception e) {
            // 自检/补结构自身出错不该拦住启动：服务照起，问题写进日志
            log.warn("启动结构自检未完成（不影响启动，但相关功能可能报错）：{}", e.getMessage());
        }
    }

    private void checkAndRepair() {
        Map<String, List<String>> missing = missingObjects();
        if (missing.isEmpty()) {
            log.info("启动自检通过：customer_db 的表与列都齐全");
            return;
        }

        log.warn("检测到数据库结构缺失，涉及 {} 个增量脚本：{}",
                missing.size(), String.join("、", missing.keySet()));
        for (Map.Entry<String, List<String>> entry : missing.entrySet()) {
            log.warn("  缺少 {} → 来自 {}", String.join("、", entry.getValue()), entry.getKey());
        }

        if (!autoMigrate) {
            log.error("已关闭自动补结构（yunti.schema.auto-migrate=false），请手动执行："
                    + "bash scripts/migrate-customer-db.sh");
            return;
        }

        // 按固定顺序执行缺失对象所属的脚本；脚本自带 IF NOT EXISTS，可重复执行
        for (String script : SCRIPT_ORDER) {
            if (!missing.containsKey(script)) {
                continue;
            }
            runScript(script);
        }

        Map<String, List<String>> stillMissing = missingObjects();
        if (stillMissing.isEmpty()) {
            log.info("已自动补齐数据库结构（执行的脚本：{}）", String.join("、", missing.keySet()));
            return;
        }
        log.error("\n数据库结构仍不完整，请手动执行：bash scripts/migrate-customer-db.sh\n  仍缺少：{}",
                stillMissing);
    }

    private Map<String, List<String>> missingObjects() {
        Set<String> tables = new HashSet<>(schemaMapper.selectTables());
        Set<String> columns = new HashSet<>(schemaMapper.selectColumns());
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : REQUIRED_TABLES.entrySet()) {
            if (!tables.contains(entry.getKey())) {
                result.computeIfAbsent(entry.getValue(), key -> new ArrayList<>())
                        .add("表 " + entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : REQUIRED_COLUMNS.entrySet()) {
            if (!columns.contains(entry.getKey())) {
                result.computeIfAbsent(entry.getValue(), key -> new ArrayList<>())
                        .add("列 " + entry.getKey());
            }
        }
        return result;
    }

    private void runScript(String script) {
        ClassPathResource resource = new ClassPathResource("schema/" + script);
        if (!resource.exists()) {
            log.error("找不到增量脚本 schema/{}，请手动执行 bash scripts/migrate-customer-db.sh", script);
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            // 显式按 UTF-8 读：脚本里有中文注释，别让默认编码把它读坏
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(resource, StandardCharsets.UTF_8));
            log.info("已执行增量脚本 schema/{}", script);
        } catch (Exception e) {
            log.error("执行增量脚本 schema/{} 失败：{}，可手动执行 bash scripts/migrate-customer-db.sh",
                    script, e.getMessage());
        }
    }
}
