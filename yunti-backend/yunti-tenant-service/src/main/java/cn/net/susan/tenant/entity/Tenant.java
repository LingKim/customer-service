package cn.net.susan.tenant.entity;

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
@TableName("tenant")
public class Tenant {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private Long enterpriseId;
    private Long planId;
    private Integer status;
    private LocalDateTime expireTime;
    private Integer isolationMode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String editor;
    @TableField("is_deleted")
    private Boolean deleted;
}
