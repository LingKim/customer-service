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
 * kb_document 知识文档：人看的这一层（标题、正文、分类、状态）。
 *
 * <p>检索用的那一层是 {@link KbChunk}：一份文档切出来的若干片段 + 向量。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_document")
public class KbDocument {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String docNo;

    private Long categoryId;

    private String title;

    /** 正文（手工录入或解析出来的全文） */
    private String content;

    private String summary;

    /** 来源码：1-手工、2-导入、3-AI生成 */
    private Integer sourceType;

    /** 原文件在 file_meta 里的 ID */
    private Long fileId;

    /** 状态码：1-草稿、2-审核中、3-已发布、4-已下线 */
    private Integer status;

    private Integer hitCount;

    private java.math.BigDecimal usefulRate;

    private String author;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;

    /** 切片数（向量化完成后回填） */
    private Integer chunkCount;

    /** 索引状态：1-未索引、2-索引中、3-已索引、4-索引失败 */
    private Integer indexStatus;

    /** 索引说明 / 失败原因 */
    private String indexMessage;

    private String fileName;

    private Long fileSize;
}
