package cn.net.susan.customer.service;

import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.CustomerEvent;
import cn.net.susan.customer.mapper.CustomerEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 客户动态的<b>唯一写入口</b>。
 *
 * <p>为什么单独抽一个组件：客户动态有 16 类事件，写它的地方却跨了三个模块——
 * 会话（发起 / 结束 / 转人工）、工单（转工单）、客户 360（打标 / 改档案 / 合并 / 匿名化 / 敏感信息查阅）。
 * 如果各自 {@code mapper.insert}，事件类型的常量会散在五六个文件里，
 * 半年后没人说得清"9 到底是转人工还是转工单"。这里集中定义 + 集中写。</p>
 *
 * <p>两个刻意的约定：</p>
 * <ol>
 *   <li><b>写失败不抛异常</b>：动态是"记录"，不是业务流程本身。建档、结束会话、建单
 *       不能因为一条动态写不进去就失败——只记 WARN 日志；</li>
 *   <li><b>标题和内容都截断到列宽</b>：宁可少几个字，也不要在生产上抛
 *       "value too long for type character varying"。</li>
 * </ol>
 */
@Component
public class CustomerDynamics {

    private static final Logger log = LoggerFactory.getLogger(CustomerDynamics.class);

    /* ---------------- 事件类型（与 customer_event.ck_customer_event_type 一一对应） ---------------- */
    /** 建档 */
    public static final int CREATE = 1;
    /** 打标 */
    public static final int TAG_ADD = 2;
    /** 去标 */
    public static final int TAG_REMOVE = 3;
    /** 等级调整 */
    public static final int LEVEL = 4;
    /** 备注更新 */
    public static final int REMARK = 5;
    /** 风险标记 */
    public static final int RISK = 6;
    /** 发起会话 */
    public static final int SESSION_START = 7;
    /** 会话结束 */
    public static final int SESSION_END = 8;
    /** 转人工 */
    public static final int HUMAN_TRANSFER = 9;
    /** 转工单 */
    public static final int TICKET_CREATE = 10;
    /** 满意度评价 */
    public static final int CSAT = 11;
    /** 敏感信息查阅（手机号这类） */
    public static final int PII_VIEW = 12;
    /** 订单变更 */
    public static final int ORDER = 13;
    /** 客户合并 */
    public static final int MERGE = 14;
    /** 客户匿名化 */
    public static final int ANONYMIZE = 15;
    /** 客户删除 */
    public static final int DELETE = 16;

    /* ---------------- 关联对象类型 ---------------- */
    public static final int REF_SESSION = 1;
    public static final int REF_TICKET = 2;
    public static final int REF_ORDER = 3;
    public static final int REF_TAG = 4;

    private final CustomerEventMapper customerEventMapper;
    private final SnowflakeIdGenerator idGenerator;

    public CustomerDynamics(CustomerEventMapper customerEventMapper,
                            SnowflakeIdGenerator idGenerator) {
        this.customerEventMapper = customerEventMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 记一条客户动态。
     *
     * @param operatorId   操作人 ID（null 表示系统 / 规则引擎）
     * @param operatorName 操作人姓名
     */
    public void record(String tenantCode, Long customerId, int eventType, String title, String content,
                       Integer refType, String refNo, Long operatorId, String operatorName) {
        if (tenantCode == null || tenantCode.isBlank() || customerId == null) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            customerEventMapper.insert(CustomerEvent.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .customerId(customerId)
                    .eventType(eventType)
                    .eventTitle(clip(title, 64))
                    .eventContent(clip(content, 512))
                    .refType(refType)
                    .refNo(clip(refNo, 64))
                    .operatorId(operatorId)
                    .operatorName(clip(operatorName, 64))
                    .eventTime(now)
                    .createTime(now)
                    .build());
        } catch (Exception e) {
            log.warn("写客户动态失败 tenant={} customerId={} type={} error={}",
                    tenantCode, customerId, eventType, e.getMessage());
        }
    }

    /** 系统动作（规则引擎、会话生命周期）的简写 */
    public void system(String tenantCode, Long customerId, int eventType, String title, String content,
                       Integer refType, String refNo) {
        record(tenantCode, customerId, eventType, title, content, refType, refNo, null, "系统");
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
