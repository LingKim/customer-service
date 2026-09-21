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
 * customer_db.file_meta 文件元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("file_meta")
public class FileMeta {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private String fileNo;
    private String fileName;
    private String objectKey;
    private Long fileSize;
    private String mimeType;
    private Integer bizType;
    private Long bizId;
    private LocalDateTime createTime;
    private String creator;
    private LocalDateTime updateTime;
    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
