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
 * customer_tag 客户标签关联（哪个客户被打上了哪个标签）。
 *
 * <p>唯一性是"租户 + 客户 + 标签定义"（软删的不算），所以重复点"打标"不会插出两条；
 * 去标是**软删**——坐席后天翻账时还要看到"这个标签是什么时候被谁摘掉的"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("customer_tag")
public class CustomerTag {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long customerId;

    /** 标签名（冗余，历史数据可能只有名字没有 tag_id） */
    private String tagName;

    /** 标签定义 ID */
    private Long tagId;

    private String tagGroup;

    /** 来源码：1-手工打标、2-规则自动、3-批量导入 */
    private Integer source;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableField("is_deleted")
    private Boolean deleted;
}
