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

/**
 * qa_rule 质检规则。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("qa_rule")
public class QaRule {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String ruleName;

    /** 类型码：1-敏感词、2-承诺规范、3-必答项、4-情绪识别 */
    private Integer ruleType;

    private String ruleContent;

    private Integer weight;

    private Boolean isEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
