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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("channel")
public class Channel {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private String channelId;
    private Integer channelType;
    private String name;
    @TableField("\"desc\"")
    private String desc;
    private Long skillGroupId;
    private Integer status;
    private Integer stage;
    private Boolean isEnabled;
    /** 允许访客接入的来源域名，逗号分隔。 */
    private String allowedOrigins;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String editor;
    @TableField("is_deleted")
    private Boolean deleted;
}
