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
 * kb_chunk 知识切片：检索的最小单位，一行一块。
 *
 * <p>写入方是 yunti-ai（它负责向量化），读取方是 customer-service（切片预览、检索结果展示）。
 * 向量列不映射成实体字段——Java 侧不需要把它读出来，留在库里给 pgvector 用就行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_chunk")
public class KbChunk {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long docId;

    /** 文档内切片序号，从 1 开始 */
    private Integer chunkNo;

    private String content;

    private Integer charCount;

    private Integer tokenCount;

    /** 生成这个向量的模型（换模型要重建索引） */
    private String embeddingModel;

    private LocalDateTime createTime;
}
