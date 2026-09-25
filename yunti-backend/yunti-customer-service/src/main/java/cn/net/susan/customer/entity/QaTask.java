package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * qa_task 质检任务。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("qa_task")
public class QaTask {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String taskNo;

    private Long sessionId;

    /** 被检坐席用户 ID */
    private Long agentId;

    private BigDecimal aiScore;

    /** AI 初检 JSON：会话名/坐席名/命中规则/评语 */
    private String aiResult;

    /** 风险码：1-低、2-中、3-高 */
    private Integer riskLevel;

    /** 状态码：1-待复核、2-已通过、3-已驳回 */
    private Integer status;

    private Long reviewerId;

    private BigDecimal reviewScore;

    private LocalDateTime reviewTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
