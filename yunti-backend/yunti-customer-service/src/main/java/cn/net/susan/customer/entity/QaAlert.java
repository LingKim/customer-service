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
 * qa_alert 会话实时质检告警：一句话命中规则就落一条，坐席当场就能看到。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("qa_alert")
public class QaAlert {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long sessionId;

    /** 会话号：坐席点告警直接跳这条会话 */
    private String sessionNo;

    /** 告警发生时该会话的负责坐席（可能还没人接） */
    private Long agentId;

    /** 触发告警的消息 */
    private Long messageId;

    private Long messageSeq;

    private Long ruleId;

    private String ruleName;

    /** 规则类型：1-敏感词、2-承诺规范、3-必答项、4-情绪识别 */
    private Integer ruleType;

    /** 告警级别：1-提示、2-警告、3-严重 */
    private Integer severity;

    /** 命中的词（敏感词类规则才有） */
    private String hitKeyword;

    /** 命中片段 */
    private String snippet;

    /** 处置建议 */
    private String advice;

    /** 状态码：1-待处理、2-已处理 */
    private Integer status;

    private Long handlerId;

    private String handleRemark;

    private LocalDateTime handleTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
