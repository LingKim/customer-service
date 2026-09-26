package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * customer_tag_def 客户标签定义（标签体系）。
 *
 * <p>和 customer_tag 的分工：这张表回答"我们这个租户有哪些标签、分成哪几组、长什么颜色"，
 * customer_tag 回答"哪个客户被打上了哪个标签"。分两张表是因为标签要能改名字、换分组、
 * 停用——改一次定义，所有打了这个标签的客户跟着变；如果标签名直接存在关联表里，
 * 改一次名字得全表更新，还会漏掉"历史名字"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("customer_tag_def")
public class CustomerTagDef {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 标签编码（租户内唯一） */
    private String tagCode;

    private String tagName;

    /** 分组：价值 / 服务 / 风险 / 偏好 / 来源 */
    private String tagGroup;

    /** 展示颜色：blue / green / orange / red / purple / gray */
    private String color;

    /** 类型码：1-手工打标、2-规则自动 */
    private Integer tagType;

    /** 自动标签的命中口径说明 */
    private String ruleHint;

    /** 规则指标：TOTAL_VALUE / ORDERS / POINTS / CSAT / LEVEL / RISK_LEVEL / SESSIONS /
     *  TICKETS / REFUND_SESSIONS / COMPLAINT_SESSIONS / NIGHT_SESSIONS / ACTIVE_DAYS */
    private String ruleMetric;

    /** 比较符：GT / GTE / LT / LTE / EQ */
    private String ruleOp;

    /** 阈值 */
    private BigDecimal ruleValue;

    /** 统计窗口（天）：0 表示全周期，只对会话类指标有意义 */
    private Integer ruleWindowDays;

    private String description;

    /** 组内排序号（越小越靠前） */
    private Integer sortNo;

    private Boolean isEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
