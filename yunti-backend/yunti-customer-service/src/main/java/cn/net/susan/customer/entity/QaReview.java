package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * qa_review 质检复核记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("qa_review")
public class QaReview {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long taskId;

    private Long reviewerId;

    /** 动作码：1-通过、2-驳回、3-重检 */
    private Integer action;

    private String comment;

    private LocalDateTime reviewTime;

    private LocalDateTime createTime;
}
