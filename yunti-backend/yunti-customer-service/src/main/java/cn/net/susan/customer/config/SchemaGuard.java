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
import java.util.HashMap;
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
            "customer_db_qa_timeout.sql",
            "customer_db_kb.sql",
            "customer_db_bot_brain.sql",
            "customer_db_chat_image.sql",
            "customer_db_ticket.sql",
            "customer_db_customer360.sql",
            "customer_db_metrics.sql"
    );

    /** 代码依赖的表 → 来源脚本 */
    /** 列宽规则对应的来源脚本（列宽规则少，单独一张小表更清楚） */
    private static final Map<String, String> SCRIPT_OF_LENGTH = Map.of(
            "file_meta.mime_type", "customer_db_kb.sql"
    );

    private static final Map<String, String> REQUIRED_TABLES = new LinkedHashMap<>();

    /** 代码依赖的列（表.列）→ 来源脚本 */
    private static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>();

    /**
     * 列宽下限（表.列 → 最少多少字符）→ 来源脚本。
     *
     * <p>只检查"列在不在"是不够的：列在、但太短，写入照样炸。
     * 典型例子是 ``file_meta.mime_type`` —— 原来 varchar(64)，
     * 而 docx 的标准 MIME 有 71 个字符，上传 Word 必报 value too long。</p>
     */
    private static final Map<String, Integer> REQUIRED_MIN_LENGTH = new LinkedHashMap<>();

    /**
     * 必需约束取值：约束名 → (必须出现的字面量, 来源脚本, 说明)。
     *
     * <p>列都在、宽度也够，插入仍然可能被 CHECK 挡下来——
     * 聊天图片的 `file_meta.biz_type=5` 就是这么被 `biz_type IN (1,2,3,4)` 拦住的，
     * 报错发生在插入那一刻，离"少跑一个脚本"隔着好几层调用栈。
     * 启动时拿约束文本比一下，就能在启动日志里说清楚。</p>
     *
     * <p>Postgres 会把 `IN (1,2,3,4)` 规范化成 `= ANY (ARRAY['1'::smallint, ...])`，
     * 所以这里比的是带引号的字面量。</p>
     */
    private static final Map<String, ConstraintRule> REQUIRED_CONSTRAINTS = new LinkedHashMap<>();

    private record ConstraintRule(String literal, String script, String note) {
    }

    static {
        REQUIRED_TABLES.put("agent_status", "customer_db_routing.sql");
        REQUIRED_TABLES.put("qa_rule", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_task", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_review", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_alert", "customer_db_realtime_qa.sql");
        REQUIRED_TABLES.put("kb_chunk", "customer_db_kb.sql");
        REQUIRED_TABLES.put("kb_document", "customer_db_kb.sql");
        REQUIRED_TABLES.put("kb_category", "customer_db_kb.sql");
        REQUIRED_TABLES.put("ticket_sla_rule", "customer_db_ticket.sql");
        REQUIRED_TABLES.put("notification", "customer_db_ticket.sql");
        REQUIRED_TABLES.put("notification_read", "customer_db_ticket.sql");
        REQUIRED_TABLES.put("customer_tag_def", "customer_db_customer360.sql");
        REQUIRED_TABLES.put("customer_tag", "customer_db_customer360.sql");
        REQUIRED_TABLES.put("customer_event", "customer_db_customer360.sql");
        REQUIRED_TABLES.put("csat_record", "customer_db_customer360.sql");
        REQUIRED_TABLES.put("agent_daily_metric", "customer_db_metrics.sql");

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
        REQUIRED_COLUMNS.put("kb_document.chunk_count", "customer_db_kb.sql");
        REQUIRED_COLUMNS.put("kb_document.index_status", "customer_db_kb.sql");
        REQUIRED_COLUMNS.put("session.bot_transfer_reason", "customer_db_bot_brain.sql");
        REQUIRED_COLUMNS.put("ticket.session_no", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket.first_response_due", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket.resolve_due", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket.sla_alerted", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket.source_channel", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket_event.operator_name", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket_sla_rule.first_response_minutes", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("customer.customer_type", "customer_db_customer360.sql");
        REQUIRED_COLUMNS.put("customer_tag_def.rule_op", "customer_db_customer360.sql");
        REQUIRED_COLUMNS.put("customer_tag_def.rule_value", "customer_db_customer360.sql");
        REQUIRED_COLUMNS.put("agent_daily_metric.csat_score", "customer_db_metrics.sql");
        REQUIRED_COLUMNS.put("agent_daily_metric.transfer_count", "customer_db_metrics.sql");

        REQUIRED_MIN_LENGTH.put("file_meta.mime_type", 128);

        // 聊天图片要写进 file_meta，biz_type=5 必须在允许列表里
        REQUIRED_CONSTRAINTS.put("ck_file_meta_biz_type",
                new ConstraintRule("5", "customer_db_chat_image.sql",
                        "file_meta.biz_type 允许 5-聊天图片"));
        REQUIRED_CONSTRAINTS.put("ck_ticket_status",
                new ConstraintRule("5", "customer_db_ticket.sql", "ticket.status 允许 5-已关闭"));
        REQUIRED_CONSTRAINTS.put("ck_ticket_event_event_type",
                new ConstraintRule("8", "customer_db_ticket.sql", "ticket_event.event_type 允许 8-SLA预警"));
        REQUIRED_CONSTRAINTS.put("ck_ticket_source_channel",
                new ConstraintRule("5", "customer_db_ticket.sql", "ticket.source_channel 允许 5-其它"));
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
            checkScriptCopies();
            checkAndRepair();
        } catch (Exception e) {
            // 自检/补结构自身出错不该拦住启动：服务照起，问题写进日志
            log.warn("启动结构自检未完成（不影响启动，但相关功能可能报错）：{}", e.getMessage());
        }
    }

    /**
     * 核对"增量脚本的运行期副本"是否齐全。
     *
     * <p>根目录 {@code schema/} 是权威版本，运行期执行的是
     * {@code resources/schema/} 下的副本。改脚本时只改根目录、忘了同步副本，
     * 后果很难看：自检能发现"少一列"，却因为找不到脚本而补不上，
     * 最后在业务查询里炸成 {@code column "xxx" does not exist}——
     * 报错点离真正的原因（少拷一个文件）隔着好几层调用栈。</p>
     *
     * <p>所以启动时先把这件事说清楚，别等到业务报错。</p>
     */
    private void checkScriptCopies() {
        List<String> missing = new ArrayList<>();
        for (String script : SCRIPT_ORDER) {
            if (!new ClassPathResource("schema/" + script).exists()) {
                missing.add(script);
            }
        }
        if (missing.isEmpty()) {
            log.info("增量脚本副本齐全（{} 个）", SCRIPT_ORDER.size());
            return;
        }
        log.error("\n运行期缺少增量脚本副本：{}\n"
                        + "  根目录 schema/ 是权威版本，请把文件复制到 "
                        + "yunti-customer-service/src/main/resources/schema/ 后重新打包启动；\n"
                        + "  或者直接手动执行：bash scripts/migrate-customer-db.sh",
                String.join("、", missing));
    }

    private void checkAndRepair() {
        Map<String, List<String>> missing = missingObjects();
        missing.putAll(missingLengths());
        missing.putAll(missingConstraints());
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
        stillMissing.putAll(missingLengths());
        stillMissing.putAll(missingConstraints());
        if (stillMissing.isEmpty()) {
            log.info("已自动补齐数据库结构（执行的脚本：{}）", String.join("、", missing.keySet()));
            return;
        }
        log.error("\n数据库结构仍不完整，请手动执行：bash scripts/migrate-customer-db.sh\n  仍缺少：{}",
                stillMissing);
    }

    private Map<String, List<String>> missingLengths() {
        Map<String, Integer> widths = new HashMap<>();
        for (Map<String, Object> row : schemaMapper.selectColumnLengths()) {
            String key = row.get("column_key") == null ? null : String.valueOf(row.get("column_key"));
            Object length = row.get("max_length");
            if (key != null && length instanceof Number number) {
                widths.put(key, number.intValue());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : REQUIRED_MIN_LENGTH.entrySet()) {
            Integer actual = widths.get(entry.getKey());
            if (actual != null && actual < entry.getValue()) {
                result.computeIfAbsent(SCRIPT_OF_LENGTH.getOrDefault(entry.getKey(), "customer_db_kb.sql"),
                                key -> new ArrayList<>())
                        .add("列 " + entry.getKey() + " 宽度 " + actual + " < " + entry.getValue());
            }
        }
        return result;
    }

    /**
     * 约束取值检查：约束在、但没放开我们要用的取值 → 照样要跑脚本。
     *
     * <p>查不到约束（比如表还没建）不算问题：那种情况上面的"缺表"检查会先报出来。</p>
     */
    private Map<String, List<String>> missingConstraints() {
        if (REQUIRED_CONSTRAINTS.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> definitions = new HashMap<>();
        try {
            for (Map<String, Object> row : schemaMapper.selectConstraintDefs()) {
                String name = row.get("constraint_name") == null ? null : String.valueOf(row.get("constraint_name"));
                if (name != null) {
                    definitions.put(name, row.get("definition") == null ? "" : String.valueOf(row.get("definition")));
                }
            }
        } catch (Exception e) {
            log.warn("读取约束定义失败，跳过约束自检：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ConstraintRule> entry : REQUIRED_CONSTRAINTS.entrySet()) {
            String definition = definitions.get(entry.getKey());
            if (definition == null || !java.util.regex.Pattern.compile(
                    "(?<!\\d)" + java.util.regex.Pattern.quote(entry.getValue().literal()) + "(?!\\d)")
                    .matcher(definition).find()) {
                result.computeIfAbsent(entry.getValue().script(), key -> new ArrayList<>())
                        .add(entry.getValue().note());
            }
        }
        return result;
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
