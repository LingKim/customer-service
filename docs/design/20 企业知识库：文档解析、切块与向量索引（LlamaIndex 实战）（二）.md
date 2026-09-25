---
title: "20 企业知识库：文档解析、切块与向量索引（LlamaIndex 实战）（二）"
source: "https://articles.zsxq.com/id_7n3fsjmbegwb.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-20
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 五、customer-service：文档台账与索引编排

分工：**Java 管"文档是什么"**（标题、分类、状态、原文件、权限与租户隔离），**Python 管"文档怎么被检索到"**（解析、切块、向量化、检索）。交界就是 `KbAiClient` 那几个方法 + 同一张 `kb_chunk` 表。

### 5.1 实体

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/KbDocument.java

这个文件是全新的，直接整份新建：

``` code-block-container
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
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/KbChunk.java

这个文件是全新的，直接整份新建：

``` code-block-container
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
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/KbCategory.java

这个文件是全新的，直接整份新建：

``` code-block-container
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
 * kb_category 知识分类：知识库左侧那棵树，用来把文档按业务线归档。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_category")
public class KbCategory {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 父分类ID，0 表示顶级 */
    private Long parentId;

    private String name;

    private Integer sortNo;

    private Boolean isEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
```

### 5.2 Mapper 与 SQL

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/KbDocumentMapper.java

这个文件是全新的，直接整份新建：

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.KbDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * kb_document 知识文档 Mapper。
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    /**
     * 文档列表：分类 / 状态 / 关键词三个条件都能组合，按更新时间倒序。
     *
     * @param keyword 模糊匹配编号 / 标题 / 摘要 / 原文件名
     */
    List<KbDocument> selectDocuments(
            @Param("tenantCode") String tenantCode,
            @Param("categoryId") Long categoryId,
            @Param("status") Integer status,
            @Param("keyword") String keyword,
            @Param("limit") int limit
    );

    /**
     * 知识库总览：文档数、已发布数、草稿数、切片总数。
     */
    Map<String, Object> selectOverview(@Param("tenantCode") String tenantCode);
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/KbChunkMapper.java

这个文件是全新的，直接整份新建：

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.KbChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * kb_chunk 知识切片 Mapper（Java 侧只读：写入由 yunti-ai 完成）。
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 某个文档的切片（按序号正序）。
     *
     * <p>注意只查展示需要的列：向量列有 1024 个浮点数，取出来既慢又没人用。</p>
     */
    List<KbChunk> selectByDoc(
            @Param("tenantCode") String tenantCode,
            @Param("docId") Long docId,
            @Param("limit") int limit
    );

    /** 删除某个文档的全部切片（文档删除 / 下线时调用）。 */
    int deleteByDoc(@Param("tenantCode") String tenantCode, @Param("docId") Long docId);
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/KbCategoryMapper.java

这个文件是全新的，直接整份新建：

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.KbCategory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * kb_category 知识分类 Mapper。
 */
@Mapper
public interface KbCategoryMapper extends BaseMapper<KbCategory> {
}
```

启动自检除了"表/列在不在"，这一篇还要查"**列够不够宽**"——理由见第五章：列在、但太短，写入照样炸。所以元数据查询要多读一个字段：

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/SchemaMapper.java

改动点：元数据查询加"字符列的最大长度"。

这个文件一共 2 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 3 行附近）**

原来是这样：

``` code-block-container
import java.util.List;

/**
 * 读库里的元数据（有哪些表、哪些列），给启动自检用。
 */
@Mapper
```

改成：

``` code-block-container
import java.util.List;
import java.util.Map;

/**
 * 读库里的元数据（有哪些表、哪些列、列有多宽），给启动自检用。
 */
@Mapper
```

**新增 2（第 16 行附近）**

原来是这样：

``` code-block-container
    /** 当前库 public schema 下所有 "表名.列名" */
    List<String> selectColumns();
}
```

改成：

``` code-block-container
    /** 当前库 public schema 下所有 "表名.列名" */
    List<String> selectColumns();

    /**
     * 字符类型列的宽度："表名.列名" → 最大字符数。
     *
     * <p>只检查"列在不在"不够：列在但太短，写入照样报 value too long
     * （file_meta.mime_type 就是这么被 Office 文档的 MIME 撑爆的）。</p>
     */
    List<Map<String, Object>> selectColumnLengths();
}
```

### 改动：yunti-backend/yunti-customer-service/src/main/resources/mapper/SchemaMapper.xml

改动点：加查询字符列宽度的 SQL。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 14 行附近）**

原来是这样：

``` code-block-container
    </select>
</mapper>
```

改成：

``` code-block-container
    </select>
    <!-- 字符类型的列宽：自检据此判断"列够不够宽" -->
    <select id="selectColumnLengths" resultType="java.util.Map">
        SELECT table_name || '.' || column_name AS column_key,
               character_maximum_length       AS max_length
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND character_maximum_length IS NOT NULL
    </select>
</mapper>
```

### 文件：yunti-backend/yunti-customer-service/src/main/resources/mapper/KbDocumentMapper.xml

这个文件是全新的，直接整份新建：

``` code-block-container
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="cn.net.susan.customer.mapper.KbDocumentMapper">

    <select id="selectDocuments" resultType="cn.net.susan.customer.entity.KbDocument">
        SELECT id, tenant_code, doc_no, category_id, title, summary, source_type, file_id,
               status, hit_count, useful_rate, author, publish_time,
               chunk_count, index_status, index_message, file_name, file_size,
               create_time, update_time, creator, editor, is_deleted
          FROM kb_document
         WHERE tenant_code = #{tenantCode}
           AND is_deleted = FALSE
           <if test="categoryId != null">
           AND category_id = #{categoryId}
           </if>
           <if test="status != null">
           AND status = #{status}
           </if>
           <if test="keyword != null and keyword != ''">
           AND (doc_no ILIKE '%' || #{keyword} || '%'
                OR title ILIKE '%' || #{keyword} || '%'
                OR summary ILIKE '%' || #{keyword} || '%'
                OR file_name ILIKE '%' || #{keyword} || '%')
           </if>
         ORDER BY update_time DESC, id DESC
         LIMIT #{limit}
    </select>
    <!-- 总览：文档四个状态数 + 切片总数（切片表在同一个库，一次查完） -->
    <select id="selectOverview" resultType="java.util.Map">
        SELECT (SELECT COUNT(1) FROM kb_document d
                 WHERE d.tenant_code = #{tenantCode} AND d.is_deleted = FALSE)            AS doc_total,
               (SELECT COUNT(1) FROM kb_document d
                 WHERE d.tenant_code = #{tenantCode} AND d.is_deleted = FALSE
                   AND d.status = 3)                                                      AS published,
               (SELECT COUNT(1) FROM kb_document d
                 WHERE d.tenant_code = #{tenantCode} AND d.is_deleted = FALSE
                   AND d.status = 1)                                                      AS draft,
               (SELECT COUNT(1) FROM kb_document d
                 WHERE d.tenant_code = #{tenantCode} AND d.is_deleted = FALSE
                   AND d.index_status = 3)                                                AS indexed,
               (SELECT COUNT(1) FROM kb_chunk c
                 WHERE c.tenant_code = #{tenantCode})                                     AS chunk_total
    </select>
</mapper>
```

### 文件：yunti-backend/yunti-customer-service/src/main/resources/mapper/KbChunkMapper.xml

这个文件是全新的，直接整份新建：

``` code-block-container
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="cn.net.susan.customer.mapper.KbChunkMapper">

    <!-- 切片预览：不查 embedding 列（1024 个浮点数，取出来又慢又没用） -->
    <select id="selectByDoc" resultType="cn.net.susan.customer.entity.KbChunk">
        SELECT id, tenant_code, doc_id, chunk_no, content, char_count, token_count,
               embedding_model, create_time
          FROM kb_chunk
         WHERE tenant_code = #{tenantCode}
           AND doc_id = #{docId}
         ORDER BY chunk_no
         LIMIT #{limit}
    </select>
    <delete id="deleteByDoc">
        DELETE FROM kb_chunk
         WHERE tenant_code = #{tenantCode}
           AND doc_id = #{docId}
    </delete>
</mapper>
```

### 5.3 调用 AI 服务的客户端

这里踩过坑：一开始用 `RestClient.body(byte[])` 发文件，结果 AI 服务收到的是 **0 字节**（日志里 `bytes=0`），只能回一句"文件是空的"，根本看不出是谁丢的。改成 JDK `HttpClient` + `BodyPublishers.ofByteArray`——也就是项目里 `QaAiClient` 已经在用的写法，显式把字节作为请求体，不经过消息转换器。

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/internal/KbAiClient.java

这个文件是全新的，直接整份新建：

``` code-block-container
package cn.net.susan.customer.internal;

import cn.net.susan.common.exception.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * yunti-ai 知识库客户端：解析 / 切块 / 向量化 / 检索都在 Python 侧做，这里只负责调。
 *
 * <p>为什么不在 Java 里做：文档解析要处理 docx（zip+XML）、pdf（压缩流），
 * 向量化要接 embedding 模型，这些在 Python 生态里是现成的；Java 侧专注做
 * 文档台账（kb_document）与权限、租户隔离。</p>
 *
 * <p>数据边界：kb_chunk 由 Python 写、Java 读；调用失败一律抛业务异常，
 * 让上传的人知道"索引没成功"，而不是悄悄存成一份检索不到的文档。</p>
 */
@Component
public class KbAiClient {

    private static final Logger log = LoggerFactory.getLogger(KbAiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final boolean logPayload;

    public KbAiClient(
            @Value("${yunti.ai.kb-base-url:http://127.0.0.1:9100}") String baseUrl,
            @Value("${yunti.ai.log-payload:true}") boolean logPayload
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://127.0.0.1:9100" : baseUrl.replaceAll("/+$", "");
        this.logPayload = logPayload;
    }

    /**
     * 索引一份文件：把原始字节交给 Python，由它解析、切块、向量化、落库。
     *
     * @return 索引结果（切片数、字符数、向量来源、前几块预览）
     */
    public IndexResult indexFile(String tenantCode, long docId, String fileName, byte[] payload) {
        if (payload == null || payload.length == 0) {
            // 明确挡住：以前这里会发出一个 0 字节的请求，AI 服务只能回一句"文件是空的"，
            // 光看那句提示根本猜不到是上传环节丢的内容
            throw new BizException(40001, "上传的文件是空的，请重新选择文件");
        }
        String url = baseUrl + "/api/ai/v1/kb/index"
                + "?tenant_code=" + enc(tenantCode)
                + "&doc_id=" + docId
                + "&file_name=" + enc(fileName);
        log.info("调用知识库索引 tenant={} docId={} file={} bytes={}",
                tenantCode, docId, fileName, payload.length);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/octet-stream")
                    .header("X-Tenant-Code", tenantCode)
                    .header("X-File-Name", enc(fileName))
                    // 关键：显式把字节数组作为请求体，不经过消息转换器
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (logPayload) {
                log.info("知识库索引响应 tenant={} docId={} status={} body={}",
                        tenantCode, docId, response.statusCode(), safe(response.body()));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(40001, parseDetail(response.body()));
            }
            return readResult(response.body());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "文档索引失败：" + friendly(e));
        }
    }

    /**
     * 索引一段正文（手工录入 / 编辑后的文档）。
     */
    public IndexResult indexText(String tenantCode, long docId, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", docId);
        body.put("content", content);
        try {
            String response = postJson(baseUrl + "/api/ai/v1/kb/index-text?tenant_code=" + enc(tenantCode),
                    body, tenantCode);
            if (logPayload) {
                log.info("知识库索引（正文）响应 tenant={} docId={} body={}", tenantCode, docId, safe(response));
            }
            return readResult(response);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "正文索引失败：" + friendly(e));
        }
    }

    /**
     * 检索：返回命中的切片（带相似度、所属文档）。
     */
    public List<Map<String, Object>> search(String tenantCode, String query, int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", tenantCode);
        body.put("query", query);
        body.put("topK", topK);
        try {
            String response = postJson(baseUrl + "/api/ai/v1/kb/search", body, tenantCode);
            if (logPayload) {
                log.info("知识库检索响应 tenant={} query={} body={}", tenantCode, query, safe(response));
            }
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) parsed.get("results");
            return results == null ? List.of() : results;
        } catch (Exception e) {
            throw new BizException(50001, "知识检索失败：" + friendly(e));
        }
    }

    /**
     * 删除某个文档的全部切片（文档删除 / 下线时调用）。
     */
    public void deleteChunks(String tenantCode, long docId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/kb/documents/" + docId
                            + "?tenant_code=" + enc(tenantCode)))
                    .timeout(Duration.ofSeconds(30))
                    .header("X-Tenant-Code", tenantCode)
                    .DELETE()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // 删切片失败不该拦住删文档：切片留着也只是查不到（文档已软删）
            log.warn("删除知识切片失败 tenant={} docId={} error={}", tenantCode, docId, e.getMessage());
        }
    }

    /** 健康检查结果缓存：总览页每次刷新都探一次活，会白等一个 RTT（AI 服务挂了要等 5 秒） */
    private volatile boolean healthCache = false;
    private volatile long healthCacheAt = 0L;
    /** 缓存有效期：30 秒内不再重复探活 */
    private static final long HEALTH_CACHE_MILLIS = 30_000L;

    /** 知识库探活（概览页展示"向量库是否可用"）；30 秒内走缓存。 */
    public boolean healthy() {
        long now = System.currentTimeMillis();
        if (now - healthCacheAt < HEALTH_CACHE_MILLIS) {
            return healthCache;
        }
        boolean result = probe();
        healthCache = result;
        healthCacheAt = now;
        return result;
    }

    private boolean probe() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/kb/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            String body = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
            return body != null && body.contains("\"UP\"");
        } catch (Exception e) {
            log.warn("知识库探活失败：{}", e.getMessage());
            return false;
        }
    }

    /** POST JSON：显式按 UTF-8 编码请求体，避免中文被写坏。 */
    private String postJson(String url, Map<String, Object> body, String tenantCode) throws Exception {
        String payload = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("X-Tenant-Code", tenantCode)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException(40001, parseDetail(response.body()));
        }
        return response.body();
    }

    /** 从 AI 服务的错误响应里抠出可读原因（FastAPI 的 detail 字段）。 */
    private String parseDetail(String body) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {
            });
            Object detail = parsed.get("detail");
            if (detail != null) {
                return String.valueOf(detail);
            }
        } catch (Exception ignored) {
            // 解析不出来就退回原文
        }
        return safe(body);
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 400 ? value.substring(0, 400) + "..." : value;
    }

    private IndexResult readResult(String body) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> preview = (List<Map<String, Object>>) parsed.getOrDefault("preview", List.of());
        return new IndexResult(
                str(parsed.get("docId")),
                str(parsed.get("fileName")),
                intOf(parsed.get("charCount")),
                intOf(parsed.get("chunkCount")),
                intOf(parsed.get("tokenCount")),
                str(parsed.get("embeddingModel")),
                str(parsed.get("embeddingSource")),
                preview == null ? List.of() : preview);
    }

    /** 把底层异常翻译成用户能看懂的一句话。 */
    private String friendly(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Connection refused") || message.contains("I/O error")) {
            return "AI 服务（yunti-ai）没启动，请先启动它再试";
        }
        if (message.contains("400")) {
            return message.replaceAll("^.*400[^\\]]*\\]?\\s*", "");
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intOf(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    /** 索引结果 */
    public record IndexResult(
            String docId,
            String fileName,
            int charCount,
            int chunkCount,
            int tokenCount,
            String embeddingModel,
            String embeddingSource,
            List<Map<String, Object>> preview
    ) {
    }
}
```

### 5.4 文档服务

索引状态是显式落库的（未索引 / 索引中 / 已索引 / 失败），失败原因也存下来——用户能看到"为什么这份文档检索不到"，而不是干等。

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/KbService.java

这个文件是全新的，直接整份新建：

``` code-block-container
package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.FileMeta;
import cn.net.susan.customer.entity.KbCategory;
import cn.net.susan.customer.entity.KbChunk;
import cn.net.susan.customer.entity.KbDocument;
import cn.net.susan.customer.internal.KbAiClient;
import cn.net.susan.customer.mapper.FileMetaMapper;
import cn.net.susan.customer.mapper.KbCategoryMapper;
import cn.net.susan.customer.mapper.KbChunkMapper;
import cn.net.susan.customer.mapper.KbDocumentMapper;
import cn.net.susan.customer.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业知识库：文档台账 + 索引编排。
 *
 * <p>分工：Java 管"文档是什么"（标题、分类、状态、原文件、权限与租户隔离），
 * Python 管"文档怎么被检索到"（解析、切块、向量化、检索）。两边的交界就是
 * {@link KbAiClient} 那几个方法，以及同一张 kb_chunk 表。</p>
 *
 * <p>索引状态是显式落库的（1-未索引、2-索引中、3-已索引、4-索引失败），
 * 而不是靠"有没有切片"猜——失败原因也存下来，用户能看到"为什么这份文档检索不到"。</p>
 */
@Service
public class KbService {

    private static final Logger log = LoggerFactory.getLogger(KbService.class);

    /** 文档状态：1-草稿、2-审核中、3-已发布、4-已下线 */
    public static final int STATUS_DRAFT = 1;
    public static final int STATUS_PUBLISHED = 3;

    /** 索引状态：1-未索引、2-索引中、3-已索引、4-索引失败 */
    public static final int INDEX_NONE = 1;
    public static final int INDEX_RUNNING = 2;
    public static final int INDEX_DONE = 3;
    public static final int INDEX_FAILED = 4;

    /** 来源：1-手工、2-导入、3-AI生成 */
    private static final int SOURCE_MANUAL = 1;
    private static final int SOURCE_IMPORT = 2;

    private static final int LIST_LIMIT = 200;
    private static final int CHUNK_PREVIEW_LIMIT = 200;
    private static final long MAX_UPLOAD_SIZE = 20L * 1024 * 1024;
    private static final DateTimeFormatter DOC_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KB_FILE_PREFIX = "kb/document";

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final KbCategoryMapper categoryMapper;
    private final FileMetaMapper fileMetaMapper;
    private final KbAiClient aiClient;
    private final ObjectStorage objectStorage;
    private final SnowflakeIdGenerator idGenerator;

    public KbService(
            KbDocumentMapper documentMapper,
            KbChunkMapper chunkMapper,
            KbCategoryMapper categoryMapper,
            FileMetaMapper fileMetaMapper,
            KbAiClient aiClient,
            ObjectStorage objectStorage,
            SnowflakeIdGenerator idGenerator
    ) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.categoryMapper = categoryMapper;
        this.fileMetaMapper = fileMetaMapper;
        this.aiClient = aiClient;
        this.objectStorage = objectStorage;
        this.idGenerator = idGenerator;
    }

    // ---------------------------------------------------------------- 查询

    /** 知识库总览：文档数、已发布、草稿、切片总数、向量库是否可用。 */
    public Map<String, Object> overview(LoginUser user) {
        String tenant = tenantOf(user);
        Map<String, Object> row = documentMapper.selectOverview(tenant);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docTotal", longValue(row == null ? null : row.get("doc_total")));
        result.put("published", longValue(row == null ? null : row.get("published")));
        result.put("draft", longValue(row == null ? null : row.get("draft")));
        result.put("indexed", longValue(row == null ? null : row.get("indexed")));
        result.put("chunkTotal", longValue(row == null ? null : row.get("chunk_total")));
        result.put("vectorReady", aiClient.healthy());
        result.put("categoryCount", categoryMapper.selectList(Wrappers.<KbCategory>lambdaQuery()
                .eq(KbCategory::getTenantCode, tenant)
                .eq(KbCategory::getDeleted, false)).size());
        return result;
    }

    /** 文档列表。 */
    public List<DocVO> documents(LoginUser user, Long categoryId, Integer status, String keyword) {
        String tenant = tenantOf(user);
        return documentMapper.selectDocuments(tenant, categoryId, status, blankToNull(keyword), LIST_LIMIT)
                .stream().map(this::toVO).toList();
    }

    /**
     * 文档详情（带正文，供编辑框回显）。
     *
     * <p>文件导入的文档，``kb_document.content`` 是空的——正文是解析后切块存在 kb_chunk 里的。
     * 如果直接把空正文回给前端，编辑框看起来是"这篇文档没有内容"，一保存还会被当成
     * "正文被清空"去重新索引。所以这里把切片拼回一份完整正文，并告诉前端它是从哪来的。</p>
     *
     * @return content 正文；contentSource 取值 manual（手工录入）/ chunks（由切片拼回）/ null（还没有内容）
     */
    public Map<String, Object> detail(LoginUser user, long id) {
        String tenant = tenantOf(user);
        KbDocument doc = requireDoc(tenant, id);
        String content = doc.getContent();
        String contentSource = content != null && !content.isBlank() ? "manual" : null;
        if (contentSource == null) {
            List<KbChunk> chunks = chunkMapper.selectByDoc(tenant, id, CHUNK_PREVIEW_LIMIT);
            if (!chunks.isEmpty()) {
                content = chunks.stream()
                        .map(chunk -> chunk.getContent() == null ? "" : chunk.getContent())
                        .collect(java.util.stream.Collectors.joining("\n"));
                contentSource = "chunks";
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", toVO(doc));
        result.put("content", content);
        result.put("contentSource", contentSource);
        return result;
    }

    /** 切片预览：这是"切块"这一步最直观的产物。 */
    public List<ChunkVO> chunks(LoginUser user, long id) {
        String tenant = tenantOf(user);
        requireDoc(tenant, id);
        return chunkMapper.selectByDoc(tenant, id, CHUNK_PREVIEW_LIMIT).stream()
                .map(chunk -> new ChunkVO(
                        String.valueOf(chunk.getId()),
                        chunk.getChunkNo(),
                        chunk.getContent(),
                        chunk.getCharCount() == null ? 0 : chunk.getCharCount(),
                        chunk.getTokenCount() == null ? 0 : chunk.getTokenCount(),
                        chunk.getEmbeddingModel(),
                        chunk.getCreateTime() == null ? null
                                : chunk.getCreateTime().toString().replace('T', ' ')))
                .toList();
    }

    /** 分类（平铺返回，前端自己组树）。 */
    public List<CategoryVO> categories(LoginUser user) {
        String tenant = tenantOf(user);
        return categoryMapper.selectList(Wrappers.<KbCategory>lambdaQuery()
                        .eq(KbCategory::getTenantCode, tenant)
                        .eq(KbCategory::getDeleted, false)
                        .orderByAsc(KbCategory::getParentId)
                        .orderByAsc(KbCategory::getSortNo))
                .stream()
                .map(item -> new CategoryVO(String.valueOf(item.getId()),
                        String.valueOf(item.getParentId() == null ? 0L : item.getParentId()),
                        item.getName(),
                        item.getSortNo() == null ? 0 : item.getSortNo(),
                        documentMapper.selectCount(Wrappers.<KbDocument>lambdaQuery()
                                .eq(KbDocument::getTenantCode, tenant)
                                .eq(KbDocument::getCategoryId, item.getId())
                                .eq(KbDocument::getDeleted, false))))
                .toList();
    }

    @Transactional
    public CategoryVO createCategory(LoginUser user, String name, Long parentId, Integer sortNo) {
        String tenant = tenantOf(user);
        if (name == null || name.isBlank()) {
            throw new BizException(40001, "请填写分类名称");
        }
        long exists = categoryMapper.selectCount(Wrappers.<KbCategory>lambdaQuery()
                .eq(KbCategory::getTenantCode, tenant)
                .eq(KbCategory::getName, name.trim())
                .eq(KbCategory::getParentId, parentId == null ? 0L : parentId)
                .eq(KbCategory::getDeleted, false));
        if (exists > 0) {
            throw new BizException(40002, "同级下已有同名分类");
        }
        LocalDateTime now = LocalDateTime.now();
        KbCategory category = KbCategory.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .parentId(parentId == null ? 0L : parentId)
                .name(name.trim())
                .sortNo(sortNo == null ? 0 : sortNo)
                .isEnabled(true)
                .createTime(now)
                .updateTime(now)
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        categoryMapper.insert(category);
        return new CategoryVO(String.valueOf(category.getId()),
                String.valueOf(category.getParentId()), category.getName(), category.getSortNo(), 0);
    }

    /** 检索测试：问一句，看命中了哪些切片。 */
    public Map<String, Object> search(LoginUser user, String query, Integer topK) {
        String tenant = tenantOf(user);
        if (query == null || query.isBlank()) {
            throw new BizException(40001, "请输入要检索的内容");
        }
        int size = topK == null ? 5 : Math.max(1, Math.min(topK, 20));
        List<Map<String, Object>> hits = aiClient.search(tenant, query.trim(), size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query.trim());
        result.put("results", hits.stream().map(this::toHitVO).toList());
        result.put("vectorReady", aiClient.healthy());
        return result;
    }

    // ---------------------------------------------------------------- 写

    /** 手工新建文档：落库后立刻索引正文。 */
    @Transactional
    public DocVO create(LoginUser user, String title, Long categoryId, String content, String summary) {
        String tenant = tenantOf(user);
        if (title == null || title.isBlank()) {
            throw new BizException(40001, "请填写文档标题");
        }
        LocalDateTime now = LocalDateTime.now();
        KbDocument doc = KbDocument.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .docNo(generateDocNo(tenant))
                .categoryId(categoryId)
                .title(title.trim())
                .content(content)
                .summary(blankToNull(summary))
                .sourceType(SOURCE_MANUAL)
                .status(STATUS_DRAFT)
                .hitCount(0)
                .author(user.name())
                .chunkCount(0)
                .indexStatus(INDEX_NONE)
                .createTime(now)
                .updateTime(now)
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        documentMapper.insert(doc);
        if (content != null && !content.isBlank()) {
            indexText(tenant, doc, content);
        }
        return toVO(doc);
    }

    /** 编辑文档：正文变了就重新索引（切片必须跟着变，否则检索到的是旧内容）。 */
    @Transactional
    public DocVO update(LoginUser user, long id, String title, Long categoryId, String content, String summary) {
        String tenant = tenantOf(user);
        KbDocument doc = requireDoc(tenant, id);
        // 只有"真的传了非空正文、且和库里不一样"才算正文改动。
        // 改分类/标题这种元信息时，前端会带一个空正文回来（导入型文档的正文在切片里），
        // 以前会把它当成"正文被清空"去重新索引，于是报"正文为空，没有可索引的内容"。
        String newContent = content == null || content.isBlank() ? null : content;
        boolean contentChanged = newContent != null && !newContent.equals(doc.getContent());
        KbDocument update = new KbDocument();
        update.setId(doc.getId());
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor(String.valueOf(user.userId()));
        if (title != null && !title.isBlank()) {
            update.setTitle(title.trim());
        }
        update.setCategoryId(categoryId);
        if (summary != null) {
            update.setSummary(blankToNull(summary));
        }
        if (newContent != null) {
            update.setContent(newContent);
        }
        documentMapper.updateById(update);
        doc.setTitle(update.getTitle() == null ? doc.getTitle() : update.getTitle());
        doc.setCategoryId(categoryId);
        doc.setSummary(update.getSummary());
        if (contentChanged) {
            doc.setContent(newContent);
            indexText(tenant, doc, newContent);
        }
        return toVO(requireDoc(tenant, id));
    }

    /** 上传文件建文档：文件进 RustFS，字节交给 Python 走"解析 → 切块 → 向量化"。 */
    @Transactional
    public DocVO upload(LoginUser user, MultipartFile file, Long categoryId, String title) {
        String tenant = tenantOf(user);
        if (file == null || file.isEmpty()) {
            throw new BizException(40001, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            throw new BizException(40001, "单个文件不能超过 20MB，请拆分后再上传");
        }
        // 文件名和 MIME 都要先收一下长度再落库：
        // Office 文档的 MIME 最长见过 73 个字符（pptx），文件名也见过超长的
        String original = safeFileName(file.getOriginalFilename());
        String mimeType = safeMime(file.getContentType());
        byte[] payload;
        try {
            payload = file.getBytes();
        } catch (Exception e) {
            throw new BizException(50001, "读取上传文件失败，请重试");
        }
        if (payload.length == 0) {
            // 常见于前端把手写 Content-Type 传进来、boundary 丢了：文件收到了但内容是空的
            throw new BizException(40001, "上传的文件内容是空的，请重新选择文件后再试");
        }

        long fileId = idGenerator.nextId();
        String safeName = original.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        String objectKey = KB_FILE_PREFIX + "/" + tenant + "/" + fileId + "_" + safeName;
        try (InputStream in = file.getInputStream()) {
            objectStorage.upload(objectKey, in, file.getSize(), mimeType);
        } catch (Exception e) {
            throw new BizException(50001, "文件上传到对象存储失败：" + e.getMessage());
        }
        fileMetaMapper.insert(FileMeta.builder()
                .id(fileId)
                .tenantCode(tenant)
                .fileNo("F" + fileId)
                .fileName(original)
                .objectKey(objectKey)
                .fileSize(file.getSize())
                .mimeType(mimeType)
                .bizType(3)
                .createTime(LocalDateTime.now())
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build());

        LocalDateTime now = LocalDateTime.now();
        KbDocument doc = KbDocument.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .docNo(generateDocNo(tenant))
                .categoryId(categoryId)
                .title(title == null || title.isBlank() ? original : title.trim())
                .sourceType(SOURCE_IMPORT)
                .fileId(fileId)
                .fileName(original)
                .fileSize(file.getSize())
                .status(STATUS_DRAFT)
                .hitCount(0)
                .author(user.name())
                .chunkCount(0)
                .indexStatus(INDEX_NONE)
                .createTime(now)
                .updateTime(now)
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        documentMapper.insert(doc);

        indexFile(tenant, doc, original, payload);
        return toVO(requireDoc(tenant, id_of(doc)));
    }

    /** 重新索引：文件文档回对象存储取原文件，手工文档用当前正文。 */
    @Transactional
    public DocVO reindex(LoginUser user, long id) {
        String tenant = tenantOf(user);
        KbDocument doc = requireDoc(tenant, id);
        // 有手工正文（或用户把导入文档的正文改过）时以正文为准，
        // 否则重新索引会把改过的内容用原文件覆盖回去
        if (doc.getContent() != null && !doc.getContent().isBlank()) {
            indexText(tenant, doc, doc.getContent());
            return toVO(requireDoc(tenant, id));
        }
        if (doc.getFileId() != null) {
            FileMeta meta = fileMetaMapper.selectById(doc.getFileId());
            if (meta == null) {
                markFailed(tenant, doc, "原文件记录已丢失，请重新上传");
                throw new BizException(40401, "原文件已不存在，请重新上传");
            }
            byte[] payload;
            try (InputStream in = objectStorage.download(meta.getObjectKey())) {
                payload = readAll(in);
            } catch (Exception e) {
                markFailed(tenant, doc, "从对象存储读取原文件失败：" + e.getMessage());
                throw new BizException(50001, "读取原文件失败，请稍后重试");
            }
            indexFile(tenant, doc, meta.getFileName(), payload);
        } else {
            indexText(tenant, doc, doc.getContent());
        }
        return toVO(requireDoc(tenant, id));
    }

    /** 发布 / 下线。下线时保留切片（方便再发布），删除文档时才清切片。 */
    @Transactional
    public DocVO changeStatus(LoginUser user, long id, int status) {
        String tenant = tenantOf(user);
        if (status < 1 || status > 4) {
            throw new BizException(40001, "文档状态取值 1-4");
        }
        KbDocument doc = requireDoc(tenant, id);
        if (status == STATUS_PUBLISHED && Integer.valueOf(INDEX_DONE).equals(doc.getIndexStatus()) == false) {
            throw new BizException(40001, "文档还没索引成功，发布后检索不到，请先重新索引");
        }
        KbDocument update = new KbDocument();
        update.setId(doc.getId());
        update.setStatus(status);
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor(String.valueOf(user.userId()));
        if (status == STATUS_PUBLISHED) {
            update.setPublishTime(LocalDateTime.now());
        }
        documentMapper.updateById(update);
        return toVO(requireDoc(tenant, id));
    }

    /** 删除文档：软删台账 + 清掉切片（切片留着会污染检索结果）。 */
    @Transactional
    public void delete(LoginUser user, long id) {
        String tenant = tenantOf(user);
        KbDocument doc = requireDoc(tenant, id);
        KbDocument update = new KbDocument();
        update.setId(doc.getId());
        update.setDeleted(true);
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor(String.valueOf(user.userId()));
        documentMapper.updateById(update);
        chunkMapper.deleteByDoc(tenant, id);
        aiClient.deleteChunks(tenant, id);
        log.info("知识文档已删除 tenant={} docId={} title={}", tenant, id, doc.getTitle());
    }

    // ---------------------------------------------------------------- 内部

    /** 索引一份文件并回填状态。失败要把原因落到 index_message，用户才知道为什么检索不到。 */
    private void indexFile(String tenant, KbDocument doc, String fileName, byte[] payload) {
        markRunning(tenant, doc);
        try {
            KbAiClient.IndexResult result = aiClient.indexFile(tenant, doc.getId(), fileName, payload);
            applyIndexResult(tenant, doc, result);
        } catch (BizException e) {
            markFailed(tenant, doc, e.getMessage());
            throw e;
        }
    }

    private void indexText(String tenant, KbDocument doc, String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(40001, "正文为空，没有可索引的内容");
        }
        markRunning(tenant, doc);
        try {
            KbAiClient.IndexResult result = aiClient.indexText(tenant, doc.getId(), content);
            applyIndexResult(tenant, doc, result);
        } catch (BizException e) {
            markFailed(tenant, doc, e.getMessage());
            throw e;
        }
    }

    private void markRunning(String tenant, KbDocument doc) {
        KbDocument update = new KbDocument();
        update.setId(doc.getId());
        update.setIndexStatus(INDEX_RUNNING);
        update.setIndexMessage("正在解析并建立向量索引…");
        update.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(update);
    }

    private void applyIndexResult(String tenant, KbDocument doc, KbAiClient.IndexResult result) {
        KbDocument update = new KbDocument();
        update.setId(doc.getId());
        update.setIndexStatus(INDEX_DONE);
        update.setChunkCount(result.chunkCount());
        update.setIndexMessage("已索引 " + result.chunkCount() + " 个切片（"
                + (result.embeddingModel() == null ? "-" : result.embeddingModel()) + "）");
        update.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(update);
        log.info("知识文档索引完成 tenant={} docId={} 切片={} 来源={}",
                tenant, doc.getId(), result.chunkCount(), result.embeddingSource());
    }

    private void markFailed(String tenant, KbDocument doc, String message) {
        KbDocument update = new KbDocument();
        update.setId(doc.getId());
        update.setIndexStatus(INDEX_FAILED);
        update.setIndexMessage(trim(message, 250));
        update.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(update);
        log.warn("知识文档索引失败 tenant={} docId={} reason={}", tenant, doc.getId(), message);
    }

    private KbDocument requireDoc(String tenant, long id) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null || !tenant.equals(doc.getTenantCode()) || Boolean.TRUE.equals(doc.getDeleted())) {
            throw new BizException(40401, "文档不存在");
        }
        return doc;
    }

    private DocVO toVO(KbDocument doc) {
        return new DocVO(
                String.valueOf(doc.getId()),
                doc.getDocNo(),
                doc.getCategoryId() == null ? null : String.valueOf(doc.getCategoryId()),
                doc.getTitle(),
                doc.getSummary(),
                doc.getSourceType() == null ? 1 : doc.getSourceType(),
                sourceText(doc.getSourceType()),
                doc.getStatus() == null ? 1 : doc.getStatus(),
                statusText(doc.getStatus()),
                doc.getIndexStatus() == null ? 1 : doc.getIndexStatus(),
                indexText(doc.getIndexStatus()),
                doc.getIndexMessage(),
                doc.getChunkCount() == null ? 0 : doc.getChunkCount(),
                doc.getFileName(),
                doc.getFileSize() == null ? 0L : doc.getFileSize(),
                doc.getAuthor(),
                doc.getCreateTime() == null ? null : doc.getCreateTime().toString().replace('T', ' '),
                doc.getUpdateTime() == null ? null : doc.getUpdateTime().toString().replace('T', ' '));
    }

    /** 检索结果：切片 + 相似度 + 所属文档，前端要能"点开出处"。 */
    private Map<String, Object> toHitVO(Map<String, Object> hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chunkId", String.valueOf(hit.getOrDefault("id", "")));
        row.put("docId", String.valueOf(hit.getOrDefault("doc_id", "")));
        row.put("docTitle", hit.get("doc_title"));
        row.put("chunkNo", hit.get("chunk_no"));
        row.put("content", hit.get("content"));
        row.put("score", hit.get("score"));
        row.put("vectorModel", hit.get("embedding_model"));
        return row;
    }

    private String sourceText(Integer sourceType) {
        int value = sourceType == null ? 1 : sourceType;
        return switch (value) {
            case 2 -> "文件导入";
            case 3 -> "AI 生成";
            default -> "手工录入";
        };
    }

    private String statusText(Integer status) {
        int value = status == null ? 1 : status;
        return switch (value) {
            case 2 -> "审核中";
            case 3 -> "已发布";
            case 4 -> "已下线";
            default -> "草稿";
        };
    }

    private String indexText(Integer indexStatus) {
        int value = indexStatus == null ? 1 : indexStatus;
        return switch (value) {
            case 2 -> "索引中";
            case 3 -> "已索引";
            case 4 -> "索引失败";
            default -> "未索引";
        };
    }

    private String generateDocNo(String tenant) {
        for (int i = 0; i < 10; i++) {
            String no = "KB" + DOC_TIME.format(LocalDateTime.now()) + randomCode(3);
            long count = documentMapper.selectCount(Wrappers.<KbDocument>lambdaQuery()
                    .eq(KbDocument::getTenantCode, tenant)
                    .eq(KbDocument::getDocNo, no));
            if (count == 0) {
                return no;
            }
        }
        throw new BizException(50001, "文档编号生成失败，请重试");
    }

    private String randomCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** MIME 收口：file_meta.mime_type 是 varchar(128)（Office 文档的 MIME 最长 73） */
    private String safeMime(String mime) {
        if (mime == null) {
            return null;
        }
        String value = mime.trim();
        return value.length() > 128 ? value.substring(0, 128) : value;
    }

    /** 文件名收口：file_meta.file_name 是 varchar(255)；超长时截断但保留扩展名 */
    private String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "document";
        }
        String value = name.trim();
        if (value.length() <= 255) {
            return value;
        }
        int dot = value.lastIndexOf('.');
        if (dot > 0 && value.length() - dot <= 16) {
            return value.substring(0, 255 - (value.length() - dot)) + value.substring(dot);
        }
        return value.substring(0, 255);
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private long id_of(KbDocument doc) {
        return doc.getId();
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用企业知识库");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用企业知识库");
        }
        return tenant;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    /** 文档视图 */
    public record DocVO(
            String id,
            String docNo,
            String categoryId,
            String title,
            String summary,
            int sourceType,
            String sourceText,
            int status,
            String statusText,
            int indexStatus,
            String indexStatusText,
            String indexMessage,
            int chunkCount,
            String fileName,
            long fileSize,
            String author,
            String createTime,
            String updateTime
    ) {
    }

    /** 切片视图 */
    public record ChunkVO(
            String id,
            int chunkNo,
            String content,
            int charCount,
            int tokenCount,
            String vectorModel,
            String createTime
    ) {
    }

    /** 分类视图 */
    public record CategoryVO(String id, String parentId, String name, int sortNo, long docCount) {
    }
}
```

### 5.5 控制器与配置

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/controller/KbController.java

这个文件是全新的，直接整份新建：

``` code-block-container
package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.KbService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 企业知识库：文档台账、上传索引、切片预览、检索测试。
 */
@RestController
@RequestMapping("/api/customer/kb")
public class KbController {

    private final KbService kbService;
    private final JwtTokenParser jwtTokenParser;

    public KbController(KbService kbService, JwtTokenParser jwtTokenParser) {
        this.kbService = kbService;
        this.jwtTokenParser = jwtTokenParser;
    }

    /** 总览：文档数、已发布、切片总数、向量库是否就绪 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(kbService.overview(jwtTokenParser.requireLoginUser(authorization)));
    }

    /** 文档列表 */
    @GetMapping("/documents")
    public ApiResponse<List<KbService.DocVO>> documents(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(kbService.documents(user, categoryId, status, keyword));
    }

    /** 文档详情（带回正文，供编辑框回显） */
    @GetMapping("/documents/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id
    ) {
        return ApiResponse.ok(kbService.detail(jwtTokenParser.requireLoginUser(authorization), id));
    }

    /** 切片预览：看"切块"这一步到底切成了什么样 */
    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<List<KbService.ChunkVO>> chunks(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id
    ) {
        return ApiResponse.ok(kbService.chunks(jwtTokenParser.requireLoginUser(authorization), id));
    }

    /** 手工新建文档（正文直接索引） */
    @PostMapping("/documents")
    public ApiResponse<KbService.DocVO> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody DocumentBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(kbService.create(
                user, body.title(), body.categoryId(), body.content(), body.summary()));
    }

    /** 编辑文档（正文变了会自动重新索引） */
    @PutMapping("/documents/{id}")
    public ApiResponse<KbService.DocVO> update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id,
            @RequestBody DocumentBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(kbService.update(
                user, id, body.title(), body.categoryId(), body.content(), body.summary()));
    }

    /** 上传文件建文档：文件进对象存储，内容由 AI 服务解析 + 切块 + 向量化 */
    @PostMapping("/documents/upload")
    public ApiResponse<KbService.DocVO> upload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "title", required = false) String title
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(kbService.upload(user, file, categoryId, title));
    }

    /** 重新索引（换了切块参数、或上次索引失败重试） */
    @PostMapping("/documents/{id}/reindex")
    public ApiResponse<KbService.DocVO> reindex(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id
    ) {
        return ApiResponse.ok(kbService.reindex(jwtTokenParser.requireLoginUser(authorization), id));
    }

    /** 发布 / 下线 */
    @PostMapping("/documents/{id}/status")
    public ApiResponse<KbService.DocVO> changeStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id,
            @RequestBody StatusBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(kbService.changeStatus(user, id, body.status()));
    }

    /** 删除文档（连带清掉切片） */
    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id
    ) {
        kbService.delete(jwtTokenParser.requireLoginUser(authorization), id);
        return ApiResponse.ok(null);
    }

    /** 分类列表 */
    @GetMapping("/categories")
    public ApiResponse<List<KbService.CategoryVO>> categories(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(kbService.categories(jwtTokenParser.requireLoginUser(authorization)));
    }

    /** 新建分类 */
    @PostMapping("/categories")
    public ApiResponse<KbService.CategoryVO> createCategory(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CategoryBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(kbService.createCategory(user, body.name(), body.parentId(), body.sortNo()));
    }

    /** 检索测试：问一句，看命中的切片与相似度 */
    @PostMapping("/search")
    public ApiResponse<Map<String, Object>> search(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody SearchBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(kbService.search(user, body.query(), body.topK()));
    }

    /** 新建 / 编辑文档请求体 */
    public record DocumentBody(
            @Size(max = 255)
            String title,

            Long categoryId,

            @Size(max = 200000, message = "单篇文档正文过长，请拆分后再录入")
            String content,

            @Size(max = 512)
            String summary
    ) {
    }

    /** 改状态请求体 */
    public record StatusBody(
            @NotNull(message = "请选择文档状态")
            @Min(value = 1, message = "状态取值 1-4")
            @Max(value = 4, message = "状态取值 1-4")
            Integer status
    ) {
    }

    /** 新建分类请求体 */
    public record CategoryBody(
            @NotBlank(message = "请填写分类名称")
            @Size(max = 64)
            String name,

            Long parentId,

            Integer sortNo
    ) {
    }

    /** 检索请求体 */
    public record SearchBody(
            @NotBlank(message = "请输入要检索的内容")
            @Size(max = 500)
            String query,

            @Min(value = 1, message = "最多返回 1~20 条")
            @Max(value = 20, message = "最多返回 1~20 条")
            Integer topK
    ) {
    }
}
```

### 改动：yunti-backend/yunti-customer-service/src/main/resources/application.yml

改动点：加 yunti.ai.kb-base-url。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 38 行附近）**

原来是这样：

``` code-block-container
    # yunti-ai AI 服务地址（质检初检由 Python AI 中心执行）
    qa-base-url: ${YUNTI_AI_QA_URL:http://127.0.0.1:9100}
    # 是否打印 AI 调用的完整入参/出参（排障用；生产环境可置 false 降低日志量）
    log-payload: ${YUNTI_AI_LOG_PAYLOAD:true}
```

改成：

``` code-block-container
    # yunti-ai AI 服务地址（质检初检由 Python AI 中心执行）
    qa-base-url: ${YUNTI_AI_QA_URL:http://127.0.0.1:9100}
    # 知识库（解析 / 切块 / 向量化 / 检索）也在 yunti-ai 里，默认同一个地址
    kb-base-url: ${YUNTI_AI_KB_URL:http://127.0.0.1:9100}
    # 是否打印 AI 调用的完整入参/出参（排障用；生产环境可置 false 降低日志量）
    log-payload: ${YUNTI_AI_LOG_PAYLOAD:true}
```

启动自检这一篇扩两处：一是把知识库的表列纳入检查，二是**新增"列宽"检查**——上传 Word 时踩了个超出"列在不在"范围的坑（见 5.6 节）。

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/config/SchemaGuard.java

改动点：启动自检覆盖知识库，并新增列宽检查。

这个文件一共 6 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 18 行附近）**

原来是这样：

``` code-block-container
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
```

改成：

``` code-block-container
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
```

**修改 2（第 51 行附近）**

原来是这样：

``` code-block-container
            "customer_db_realtime_qa.sql",
            "customer_db_qa_source.sql",
            "customer_db_qa_timeout.sql"
    );

    /** 代码依赖的表 → 来源脚本 */
    private static final Map<String, String> REQUIRED_TABLES = new LinkedHashMap<>();

    /** 代码依赖的列（表.列）→ 来源脚本 */
    private static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>();

    static {
        REQUIRED_TABLES.put("agent_status", "customer_db_routing.sql");
        REQUIRED_TABLES.put("qa_rule", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_task", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_review", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_alert", "customer_db_realtime_qa.sql");

        REQUIRED_COLUMNS.put("channel.allowed_origins", "customer_db_security.sql");
```

改成：

``` code-block-container
            "customer_db_realtime_qa.sql",
            "customer_db_qa_source.sql",
            "customer_db_qa_timeout.sql",
            "customer_db_kb.sql"
    );

    /** 代码依赖的表 → 来源脚本 */
    /** 列宽规则对应的来源脚本（列宽规则少，单独一张小表更清楚） */
    private static final Map<String, String> SCRIPT_OF_LENGTH = Map.of(
            "file_meta.mime_type", "customer_db_kb.sql"
    );

    private static final Map<String, String> REQUIRED_TABLES = new LinkedHashMap<>();

    /** 代码依赖的列（表.列）→ 来源脚本 */
    private static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>();

    /**
     * 列宽下限（表.列 → 最少多少字符）→ 来源脚本。
     *
     * <p>只检查"列在不在"是不够的：列在、但太短，写入照样炸。
     * 典型例子是 ``file_meta.mime_type`` —— 原来 varchar(64)，
     * 而 docx 的标准 MIME 有 71 个字符，上传 Word 必报 value too long。</p>
     */
    private static final Map<String, Integer> REQUIRED_MIN_LENGTH = new LinkedHashMap<>();

    static {
        REQUIRED_TABLES.put("agent_status", "customer_db_routing.sql");
        REQUIRED_TABLES.put("qa_rule", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_task", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_review", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_alert", "customer_db_realtime_qa.sql");
        REQUIRED_TABLES.put("kb_chunk", "customer_db_kb.sql");

        REQUIRED_COLUMNS.put("channel.allowed_origins", "customer_db_security.sql");
```

**新增 3（第 94 行附近）**

原来是这样：

``` code-block-container
        REQUIRED_COLUMNS.put("qa_rule.severity", "customer_db_realtime_qa.sql");
        REQUIRED_COLUMNS.put("qa_rule.timeout_seconds", "customer_db_qa_timeout.sql");
    }
```

改成：

``` code-block-container
        REQUIRED_COLUMNS.put("qa_rule.severity", "customer_db_realtime_qa.sql");
        REQUIRED_COLUMNS.put("qa_rule.timeout_seconds", "customer_db_qa_timeout.sql");
        REQUIRED_COLUMNS.put("kb_document.chunk_count", "customer_db_kb.sql");
        REQUIRED_COLUMNS.put("kb_document.index_status", "customer_db_kb.sql");

        REQUIRED_MIN_LENGTH.put("file_meta.mime_type", 128);
    }
```

**新增 4（第 126 行附近）**

原来是这样：

``` code-block-container
    private void checkAndRepair() {
        Map<String, List<String>> missing = missingObjects();
        if (missing.isEmpty()) {
            log.info("启动自检通过：customer_db 的表与列都齐全");
```

改成：

``` code-block-container
    private void checkAndRepair() {
        Map<String, List<String>> missing = missingObjects();
        missing.putAll(missingLengths());
        if (missing.isEmpty()) {
            log.info("启动自检通过：customer_db 的表与列都齐全");
```

**新增 5（第 153 行附近）**

原来是这样：

``` code-block-container
        Map<String, List<String>> stillMissing = missingObjects();
        if (stillMissing.isEmpty()) {
            log.info("已自动补齐数据库结构（执行的脚本：{}）", String.join("、", missing.keySet()));
```

改成：

``` code-block-container
        Map<String, List<String>> stillMissing = missingObjects();
        stillMissing.putAll(missingLengths());
        if (stillMissing.isEmpty()) {
            log.info("已自动补齐数据库结构（执行的脚本：{}）", String.join("、", missing.keySet()));
```

**新增 6（第 162 行附近）**

原来是这样：

``` code-block-container
    }

    private Map<String, List<String>> missingObjects() {
        Set<String> tables = new HashSet<>(schemaMapper.selectTables());
```

改成：

``` code-block-container
    }

    private Map<String, List<String>> missingLengths() {
        Map<String, Integer> widths = new HashMap<>();
        for (Map<String, Object> row : schemaMapper.selectColumnLengths()) {
            String key = row.get("column_key") == null ? null : String.valueOf(row.get("column_key"));
            Object length = row.get("max_length");
            if (key != null && length instanceof Number number) {
                widths.put(key, number.intValue());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : REQUIRED_MIN_LENGTH.entrySet()) {
            Integer actual = widths.get(entry.getKey());
            if (actual != null && actual < entry.getValue()) {
                result.computeIfAbsent(SCRIPT_OF_LENGTH.getOrDefault(entry.getKey(), "customer_db_kb.sql"),
                                key -> new ArrayList<>())
                        .add("列 " + entry.getKey() + " 宽度 " + actual + " < " + entry.getValue());
            }
        }
        return result;
    }

    private Map<String, List<String>> missingObjects() {
        Set<String> tables = new HashSet<>(schemaMapper.selectTables());
```

### 复制：schema/customer\_db\_kb.sql → yunti-backend/yunti-customer-service/src/main/resources/schema/customer\_db\_kb.sql

启动自检要能执行它，所以放进服务自己的 classpath。

源文件就是前面已经建好的 `schema/customer_db_kb.sql`，内容一模一样，直接在工程里复制一份到新路径即可（`cp schema/customer_db_kb.sql yunti-backend/yunti-customer-service/src/main/resources/schema/customer_db_kb.sql`）。

### 5.6 file\_meta 的列宽：`value too long` 是怎么来的

上传 Word 报过这样一个错：

``` code-block-container
INSERT INTO file_meta (id, tenant_code, file_no, file_name, object_key, file_size, mime_type, ...)
Cause: org.postgresql.util.PSQLException: ERROR: value too long for type character varying(64)
```

原因不在 MIME 类型本身，而在**列宽定小了**：`file_meta.mime_type` 是 `varchar(64)`，而 Office 文档的标准 MIME 本来就比它长——docx 71、xlsx 65、pptx 73。所以 txt / md / csv / html 都能传，**只有 Office 系文档会炸**。

三处一起改：列宽放大到 128（写进增量脚本）；启动自检新增"列够不够宽"的检查（列在、但太短，写入照样失败，这是原来的自检覆盖不到的）；写库前对 MIME 和文件名做长度收口，避免以后被更长的值撑爆。

> 这条修复对"营业执照上传"那条链路也生效——它同样往 `file_meta` 写 MIME，只是限制在 JPG/PNG/PDF，之前没被触发而已。

## 六、前端：知识库页面

### 6.1 接口封装

### 文件：yunti-frontend/src/api/customer/kb.ts

这个文件是全新的，直接整份新建：

``` code-block-container
import { request } from '../request'

/** 知识库总览 */
export interface KbOverview {
  docTotal: number
  published: number
  draft: number
  indexed: number
  chunkTotal: number
  categoryCount: number
  /** AI 服务与向量库是否就绪 */
  vectorReady: boolean
}

/** 知识文档 */
export interface KbDocItem {
  id: string
  docNo: string
  categoryId?: string | null
  title: string
  summary?: string | null
  sourceType: number
  sourceText: string
  status: number
  statusText: string
  indexStatus: number
  indexStatusText: string
  indexMessage?: string | null
  chunkCount: number
  fileName?: string | null
  fileSize: number
  author?: string | null
  createTime?: string | null
  updateTime?: string | null
}

/** 文档切片 */
export interface KbChunkItem {
  id: string
  chunkNo: number
  content: string
  charCount: number
  tokenCount: number
  vectorModel?: string | null
  createTime?: string | null
}

/** 知识分类 */
export interface KbCategoryItem {
  id: string
  parentId: string
  name: string
  sortNo: number
  docCount: number
}

/** 检索命中的切片 */
export interface KbHitItem {
  chunkId: string
  docId: string
  docTitle?: string | null
  chunkNo: number
  content: string
  score: number
  vectorModel?: string | null
}

export interface KbSearchResult {
  query: string
  results: KbHitItem[]
  vectorReady: boolean
  /**
   * 检索模式：
   * vector-向量检索（真实语义）、keyword-local-vector-没配向量密钥退化成关键词、
   * keyword-向量无结果后兜底关键词、empty-空查询
   */
  mode?: string
}

export function fetchKbOverview(): Promise<KbOverview> {
  return request<KbOverview>({ url: '/customer/kb/overview', method: 'get' })
}

export function fetchKbDocuments(params: {
  categoryId?: string
  status?: number
  keyword?: string
} = {}): Promise<KbDocItem[]> {
  return request<KbDocItem[]>({ url: '/customer/kb/documents', method: 'get', params })
}

/** 正文来源：manual-手工录入、chunks-由切片拼回（文件导入型）、null-还没有正文 */
export type KbContentSource = 'manual' | 'chunks' | null

export function fetchKbDocumentDetail(
  id: string,
): Promise<{ document: KbDocItem; content?: string; contentSource?: KbContentSource }> {
  return request({ url: `/customer/kb/documents/${id}`, method: 'get' })
}

/** 新建文档（手工正文） */
export function createKbDocument(data: {
  title: string
  categoryId?: string | null
  content?: string
  summary?: string
}): Promise<KbDocItem> {
  return request<KbDocItem>({ url: '/customer/kb/documents', method: 'post', data })
}

export function updateKbDocument(
  id: string,
  data: { title?: string; categoryId?: string | null; content?: string; summary?: string },
): Promise<KbDocItem> {
  return request<KbDocItem>({ url: `/customer/kb/documents/${id}`, method: 'put', data })
}

/** 上传文件：文件进对象存储，内容由 AI 服务解析 + 切块 + 向量化 */
export function uploadKbDocument(file: File, categoryId?: string | null, title?: string): Promise<KbDocItem> {
  const form = new FormData()
  form.append('file', file)
  if (categoryId) {
    form.append('categoryId', categoryId)
  }
  if (title) {
    form.append('title', title)
  }
  // 注意：这里**不能**手写 Content-Type。
  // 手写成 'multipart/form-data' 会把 boundary 丢掉，服务端解析出来的文件是空的
  // （现象就是"上传成功但索引失败/文件是空的"）。交给浏览器自动带上 boundary。
  return request<KbDocItem>({
    url: '/customer/kb/documents/upload',
    method: 'post',
    data: form,
    timeout: 120000,
  })
}

export function reindexKbDocument(id: string): Promise<KbDocItem> {
  return request<KbDocItem>({ url: `/customer/kb/documents/${id}/reindex`, method: 'post', timeout: 120000 })
}

export function changeKbDocumentStatus(id: string, status: number): Promise<KbDocItem> {
  return request<KbDocItem>({ url: `/customer/kb/documents/${id}/status`, method: 'post', data: { status } })
}

export function deleteKbDocument(id: string): Promise<void> {
  return request<void>({ url: `/customer/kb/documents/${id}`, method: 'delete' })
}

export function fetchKbChunks(id: string): Promise<KbChunkItem[]> {
  return request<KbChunkItem[]>({ url: `/customer/kb/documents/${id}/chunks`, method: 'get' })
}

export function fetchKbCategories(): Promise<KbCategoryItem[]> {
  return request<KbCategoryItem[]>({ url: '/customer/kb/categories', method: 'get' })
}

export function createKbCategory(data: {
  name: string
  parentId?: string | null
  sortNo?: number
}): Promise<KbCategoryItem> {
  return request<KbCategoryItem>({ url: '/customer/kb/categories', method: 'post', data })
}

/** 检索测试 */
export function searchKb(data: { query: string; topK?: number }): Promise<KbSearchResult> {
  return request<KbSearchResult>({ url: '/customer/kb/search', method: 'post', data })
}
```

### 6.2 页面

三个能"看见"的地方，是这个页面存在的意义：**切片预览**（看每块切成了什么）、**检索测试**（问一句看命中哪块、分值多少）、**索引状态**（失败时鼠标悬停能看到原因）。

检索测试这里还有一处细节：结果区会标明这次是**向量语义检索**还是**关键词匹配**，分值标签也跟着换（相似度 / 匹配度）。没配向量密钥时会额外给一句提示——**让使用者一眼知道自己看到的分数是什么口径**，不要拿匹配度当相似度评估检索效果。

### 文件：yunti-frontend/src/views/kb/index.vue

这个文件是全新的，直接整份新建：

``` code-block-container
<template>
  <div class="kb-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">企业知识库</h2>
        <p class="page-sub">
          上传文档 → 自动解析、切块、向量化 → 机器人就能查到；这里能看到每一份文档切成了什么、检索命中哪一句
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="loading" @click="loadAll">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button @click="openSearch">
          <el-icon class="btn-icon"><Search /></el-icon>
          检索测试
        </el-button>
        <el-button @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建文档
        </el-button>
        <el-button type="primary" @click="openUpload">
          <el-icon class="btn-icon"><UploadFilled /></el-icon>
          上传文档
        </el-button>
      </div>
    </div>
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Document /></el-icon></div>
        <div><div class="stat-value">{{ overview?.docTotal ?? '—' }}</div><div class="stat-label">知识文档</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><CircleCheckFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.published ?? '—' }}</div><div class="stat-label">已发布</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><Grid /></el-icon></div>
        <div><div class="stat-value">{{ overview?.chunkTotal ?? '—' }}</div><div class="stat-label">知识切片</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber">
          <el-icon :size="18"><Connection /></el-icon>
        </div>
        <div>
          <div class="stat-value">{{ overview?.indexed ?? '—' }}</div>
          <div class="stat-label">已建索引</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" :class="overview?.vectorReady ? 'green' : 'red'">
          <el-icon :size="18"><Cpu /></el-icon>
        </div>
        <div>
          <div class="stat-value">{{ overview?.vectorReady ? '就绪' : '不可用' }}</div>
          <div class="stat-label">向量检索（pgvector）</div>
        </div>
      </div>
    </div>
    <div class="kb-body">
      <aside class="kb-side">
        <div class="side-head">
          <span>知识分类</span>
          <el-button link type="primary" size="small" @click="openCategory">新建</el-button>
        </div>
        <div class="cat-list">
          <div class="cat-item" :class="{ active: categoryId === '' }" @click="filterCategory('')">
            <span>全部文档</span>
            <span class="cat-count">{{ overview?.docTotal ?? 0 }}</span>
          </div>
          <div
            v-for="item in categories"
            :key="item.id"
            class="cat-item"
            :class="{ active: categoryId === item.id }"
            @click="filterCategory(item.id)"
          >
            <span>{{ item.name }}</span>
            <span class="cat-count">{{ item.docCount }}</span>
          </div>
          <div v-if="!categories.length" class="cat-empty">还没有分类，先建一个吧</div>
        </div>
      </aside>
      <main class="kb-main">
        <div class="filter-row">
          <el-input
            v-model="keyword"
            class="kb-search"
            clearable
            placeholder="搜索文档编号 / 标题 / 摘要 / 文件名"
            @keyup.enter="loadDocuments"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-radio-group v-model="statusFilter" @change="loadDocuments">
            <el-radio-button :value="0">全部</el-radio-button>
            <el-radio-button :value="3">已发布</el-radio-button>
            <el-radio-button :value="1">草稿</el-radio-button>
            <el-radio-button :value="4">已下线</el-radio-button>
          </el-radio-group>
        </div>
        <el-table v-loading="loading" :data="documents" stripe>
          <el-table-column type="index" label="序号" width="70" />
          <el-table-column label="文档" min-width="260">
            <template #default="{ row }">
              <div class="doc-title">{{ row.title }}</div>
              <div class="cell-sub">
                <span class="mono">{{ row.docNo }}</span>
                <el-tag v-if="row.fileName" size="small" effect="plain" class="ml-6">{{ row.fileName }}</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="{ row }">{{ row.sourceText }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(row.status)" effect="light">{{ row.statusText }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="索引" width="150">
            <template #default="{ row }">
              <el-tooltip :content="row.indexMessage || '尚未建立索引'" placement="top">
                <el-tag size="small" :type="indexTag(row.indexStatus)" effect="light">
                  {{ row.indexStatusText }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="切片" width="90">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="!row.chunkCount" @click="openChunks(row)">
                {{ row.chunkCount }} 块
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="165">
            <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" @click="reindex(row)">重新索引</el-button>
              <el-button v-if="row.status !== 3" link type="success" @click="changeStatus(row, 3)">发布</el-button>
              <el-button v-else link @click="changeStatus(row, 4)">下线</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="kb-empty">
              <div class="kb-empty-title">还没有知识文档</div>
              <div class="kb-empty-sub">
                点右上角「上传文档」传一份 txt / md / docx / pdf，系统会自动解析、切块并建立向量索引；
                也可以「新建文档」直接把正文贴进来。
              </div>
            </div>
          </template>
        </el-table>
      </main>
    </div>
    <!-- 上传文档 -->
    <el-dialog v-model="uploadVisible" title="上传文档" width="560px" :close-on-click-modal="false">
      <el-upload
        drag
        class="kb-upload"
        :auto-upload="false"
        :show-file-list="false"
        accept=".txt,.md,.markdown,.csv,.json,.log,.html,.htm,.docx,.pdf"
        :on-change="onFileChange"
      >
        <el-icon class="up-icon"><UploadFilled /></el-icon>
        <div class="up-text">把文件拖到这里，或<em>点击选择</em></div>
        <div class="up-tip">支持 txt / md / csv / html / docx / pdf，单个不超过 20MB</div>
      </el-upload>
      <div v-if="uploadFile" class="up-file">
        <el-icon><Document /></el-icon>
        <span>{{ uploadFile.name }}</span>
        <span class="up-size">{{ formatSize(uploadFile.size) }}</span>
      </div>
      <el-form label-width="80px" class="mt-12">
        <el-form-item label="文档标题">
          <el-input v-model="uploadTitle" maxlength="255" placeholder="不填就用文件名" />
        </el-form-item>
        <el-form-item label="所属分类">
          <el-select v-model="uploadCategoryId" clearable placeholder="不指定" class="full-width">
            <el-option v-for="item in categories" :key="item.id" :value="item.id" :label="item.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" :disabled="!uploadFile" @click="submitUpload">
          上传并建索引
        </el-button>
      </template>
    </el-dialog>
    <!-- 新建 / 编辑文档 -->
    <el-dialog
      v-model="formVisible"
      :title="editingId ? '编辑文档' : '新建文档'"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-form label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="255" placeholder="例如：退款政策说明" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" clearable placeholder="不指定" class="full-width">
            <el-option v-for="item in categories" :key="item.id" :value="item.id" :label="item.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="摘要">
          <el-input v-model="form.summary" maxlength="512" placeholder="一句话说明这份文档讲什么（选填）" />
        </el-form-item>
        <el-alert
          v-if="contentSource === 'chunks'"
          class="mb-10"
          type="info"
          :closable="false"
          show-icon
          title="这段正文是从上传的文件里解析出来的"
          description="导入型文档的正文存在切片中，这里给你拼回来方便查看。只改标题/分类/摘要不会重新索引；如果改动了这段正文并保存，后续就以这段正文为准重新索引。"
        />
        <el-alert
          v-else-if="formVisible && !form.content"
          class="mb-10"
          type="warning"
          :closable="false"
          show-icon
          title="这篇文档还没有正文"
          description="可以把内容贴进来，保存后会自动切块并建立向量索引。"
        />
        <el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="12"
            maxlength="200000"
            show-word-limit
            placeholder="粘贴正文；保存后系统会自动切块并建立向量索引"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存并索引</el-button>
      </template>
    </el-dialog>
    <!-- 新建分类 -->
    <el-dialog v-model="categoryVisible" title="新建知识分类" width="420px">
      <el-form label-width="70px">
        <el-form-item label="名称" required>
          <el-input v-model="categoryName" maxlength="64" placeholder="例如：售后政策" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingCategory" @click="submitCategory">创建</el-button>
      </template>
    </el-dialog>
    <!-- 切片预览 -->
    <el-drawer v-model="chunkVisible" :title="`切片预览 · ${chunkDoc?.title || ''}`" size="720px">
      <div class="chunk-tip">
        检索的最小单位就是这些切片：机器人回答问题时会先命中某几块，再把它们交给大模型。
        所以"切得合不合理"直接决定问答质量——一句话被切断、或一整篇挤成一块，都会影响召回。
      </div>
      <div v-loading="loadingChunks" class="chunk-list">
        <div v-for="item in chunks" :key="item.id" class="chunk-item">
          <div class="chunk-head">
            <span class="chunk-no">第 {{ item.chunkNo }} 块</span>
            <span class="chunk-meta">{{ item.charCount }} 字 · 约 {{ item.tokenCount }} token</span>
            <span v-if="item.vectorModel" class="chunk-model">{{ item.vectorModel }}</span>
          </div>
          <div class="chunk-content">{{ item.content }}</div>
        </div>
        <el-empty v-if="!loadingChunks && !chunks.length" description="还没有切片，先点「重新索引」" />
      </div>
    </el-drawer>
    <!-- 检索测试 -->
    <el-dialog v-model="searchVisible" title="检索测试" width="760px">
      <div class="search-tip">
        输入一句客户可能问的话，看看能不能命中正确的知识切片。命不中就说明：文档没上传、没建索引，或者切片切得不合适。
      </div>
      <div class="search-bar">
        <el-input
          v-model="searchQuery"
          placeholder="例如：退款多久能到账？"
          maxlength="500"
          @keyup.enter="doSearch"
        />
        <el-button type="primary" :loading="searching" @click="doSearch">检索</el-button>
      </div>
      <div v-if="searchResult" class="search-meta">
        共命中 {{ searchResult.results.length }} 条
        <el-tag v-if="keywordMode" size="small" type="warning" effect="light" class="ml-6">
          关键词匹配（未配向量密钥）
        </el-tag>
        <el-tag v-else-if="searchResult.results.length" size="small" type="success" effect="light" class="ml-6">
          向量语义检索
        </el-tag>
        <el-tag v-if="!searchResult.vectorReady" size="small" type="danger" effect="light" class="ml-6">
          AI 服务未就绪
        </el-tag>
        <div v-if="keywordMode" class="search-hint">
          当前没配 <code>YUNTI_AI_EMBEDDING_API_KEY</code>，只能按字面匹配（把问题拆成片段逐个找）。
          配好密钥并重新索引后，问法差几个字也能命中。
        </div>
      </div>
      <div v-loading="searching" class="hit-list">
        <div v-for="hit in searchResult?.results || []" :key="hit.chunkId" class="hit-item">
          <div class="hit-head">
            <span class="hit-doc">{{ hit.docTitle || '未命名文档' }}</span>
            <span class="hit-no">第 {{ hit.chunkNo }} 块</span>
            <span class="hit-score">{{ keywordMode ? '匹配度' : '相似度' }} {{ (hit.score ?? 0).toFixed(3) }}</span>
          </div>
          <div class="hit-content">{{ hit.content }}</div>
        </div>
        <el-empty
          v-if="searchResult && !searchResult.results.length"
          description="没有命中任何切片：确认文档已上传且索引成功"
        />
      </div>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeKbDocumentStatus,
  createKbCategory,
  createKbDocument,
  deleteKbDocument,
  fetchKbCategories,
  fetchKbChunks,
  fetchKbDocumentDetail,
  fetchKbDocuments,
  fetchKbOverview,
  reindexKbDocument,
  searchKb,
  updateKbDocument,
  uploadKbDocument,
  type KbCategoryItem,
  type KbChunkItem,
  type KbDocItem,
  type KbOverview,
  type KbSearchResult,
} from '../../api/customer/kb'

const loading = ref(false)
const overview = ref<KbOverview | null>(null)
const categories = ref<KbCategoryItem[]>([])
const documents = ref<KbDocItem[]>([])
const categoryId = ref('')
const statusFilter = ref(0)
const keyword = ref('')

const uploadVisible = ref(false)
const uploading = ref(false)
const uploadFile = ref<File | null>(null)
const uploadTitle = ref('')
const uploadCategoryId = ref('')

const formVisible = ref(false)
const saving = ref(false)
const editingId = ref('')
const form = reactive({ title: '', categoryId: '', summary: '', content: '' })
/** 当前编辑的正文是从哪来的：manual-手工录入、chunks-由切片拼回、null-还没有正文 */
const contentSource = ref<'manual' | 'chunks' | null>(null)
/**
 * 打开编辑框时看到的正文原文。
 * 保存时拿它和输入框对比：只有正文真的被改过才提交正文——
 * 否则"只改分类"也会把正文原样回传，触发一次没必要的重新索引。
 */
const loadedContent = ref('')

const categoryVisible = ref(false)
const savingCategory = ref(false)
const categoryName = ref('')

const chunkVisible = ref(false)
const loadingChunks = ref(false)
const chunks = ref<KbChunkItem[]>([])
const chunkDoc = ref<KbDocItem | null>(null)

const searchVisible = ref(false)
const searching = ref(false)
const searchQuery = ref('')
const searchResult = ref<KbSearchResult | null>(null)
/** 是否是关键词兜底模式：是的话分值是"匹配度"，不是余弦相似度，标签也要跟着换 */
const keywordMode = computed(() => (searchResult.value?.mode || '').includes('keyword'))

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadOverview(), loadCategories(), loadDocuments()])
}

async function loadOverview() {
  try {
    overview.value = await fetchKbOverview()
  } catch {
    overview.value = null
  }
}

async function loadCategories() {
  try {
    categories.value = await fetchKbCategories()
  } catch {
    categories.value = []
  }
}

async function loadDocuments() {
  loading.value = true
  try {
    documents.value = await fetchKbDocuments({
      categoryId: categoryId.value || undefined,
      status: statusFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
    })
  } finally {
    loading.value = false
  }
}

function filterCategory(id: string) {
  categoryId.value = id
  void loadDocuments()
}

/* ---------------- 上传 ---------------- */

function openUpload() {
  uploadFile.value = null
  uploadTitle.value = ''
  uploadCategoryId.value = categoryId.value || ''
  uploadVisible.value = true
}

function onFileChange(file: { raw?: File }) {
  uploadFile.value = file.raw || null
}

async function submitUpload() {
  if (!uploadFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  try {
    const doc = await uploadKbDocument(uploadFile.value, uploadCategoryId.value || undefined,
      uploadTitle.value.trim() || undefined)
    uploadVisible.value = false
    ElMessage.success(`已上传并建立索引：${doc.chunkCount} 个切片`)
    await loadAll()
  } catch {
    // 请求层已提示（索引失败的原因来自后端 index_message）
  } finally {
    uploading.value = false
  }
}

/* ---------------- 新建 / 编辑 ---------------- */

function openCreate() {
  editingId.value = ''
  form.title = ''
  form.categoryId = categoryId.value || ''
  form.summary = ''
  form.content = ''
  loadedContent.value = ''
  contentSource.value = null
  formVisible.value = true
}

async function openEdit(row: KbDocItem) {
  editingId.value = row.id
  form.title = row.title
  form.categoryId = row.categoryId || ''
  form.summary = row.summary || ''
  form.content = ''
  formVisible.value = true
  try {
    const detail = await fetchKbDocumentDetail(row.id)
    form.content = detail.content || ''
    loadedContent.value = form.content
    contentSource.value = detail.contentSource || null
  } catch {
    // 拿不到正文也不影响改标题
  }
}

async function submitForm() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写文档标题')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      const contentChanged = form.content !== loadedContent.value
      await updateKbDocument(editingId.value, {
        title: form.title.trim(),
        categoryId: form.categoryId || null,
        summary: form.summary.trim(),
        // 正文没改就不传：只改标题/分类时不做无谓的重新索引
        content: contentChanged ? form.content : undefined,
      })
      ElMessage.success(contentChanged ? '已保存，正文有改动，已重新索引' : '已保存')
    } else {
      await createKbDocument({
        title: form.title.trim(),
        categoryId: form.categoryId || null,
        summary: form.summary.trim(),
        content: form.content,
      })
      ElMessage.success('文档已创建并建立索引')
    }
    formVisible.value = false
    await loadAll()
  } catch {
    // 请求层已提示
  } finally {
    saving.value = false
  }
}

async function submitCategory() {
  if (!categoryName.value.trim()) {
    ElMessage.warning('请填写分类名称')
    return
  }
  savingCategory.value = true
  try {
    await createKbCategory({ name: categoryName.value.trim() })
    categoryVisible.value = false
    categoryName.value = ''
    ElMessage.success('分类已创建')
    await loadCategories()
  } finally {
    savingCategory.value = false
  }
}

function openCategory() {
  categoryName.value = ''
  categoryVisible.value = true
}

/* ---------------- 行操作 ---------------- */

async function reindex(row: KbDocItem) {
  try {
    const doc = await reindexKbDocument(row.id)
    ElMessage.success(`已重新索引：${doc.chunkCount} 个切片`)
    await loadAll()
  } catch {
    await loadAll()
  }
}

async function changeStatus(row: KbDocItem, status: number) {
  try {
    await changeKbDocumentStatus(row.id, status)
    ElMessage.success(status === 3 ? '已发布' : '已下线')
    await loadAll()
  } catch {
    // 请求层已提示（没索引成功不允许发布）
  }
}

async function remove(row: KbDocItem) {
  await ElMessageBox.confirm(
    `确认删除「${row.title}」吗？删除后台账和向量切片都会清掉，机器人就查不到它了。`,
    '删除文档',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '再想想' },
  )
  await deleteKbDocument(row.id)
  ElMessage.success('文档已删除')
  await loadAll()
}

async function openChunks(row: KbDocItem) {
  chunkDoc.value = row
  chunkVisible.value = true
  loadingChunks.value = true
  chunks.value = []
  try {
    chunks.value = await fetchKbChunks(row.id)
  } finally {
    loadingChunks.value = false
  }
}

/* ---------------- 检索测试 ---------------- */

function openSearch() {
  // 每次打开都清空上次的输入与结果：检索测试是"临时问一句"，
  // 留着上一次的问题容易让人以为是这次查出来的
  searchQuery.value = ''
  searchResult.value = null
  searchVisible.value = true
}

async function doSearch() {
  if (!searchQuery.value.trim()) {
    ElMessage.warning('请输入要检索的内容')
    return
  }
  searching.value = true
  try {
    searchResult.value = await searchKb({ query: searchQuery.value.trim(), topK: 5 })
  } finally {
    searching.value = false
  }
}

/* ---------------- 展示辅助 ---------------- */

function statusTag(status: number) {
  if (status === 3) return 'success'
  if (status === 4) return 'info'
  return 'warning'
}

function indexTag(indexStatus: number) {
  if (indexStatus === 3) return 'success'
  if (indexStatus === 2) return 'warning'
  if (indexStatus === 4) return 'danger'
  return 'info'
}

/** 后端给的是 ISO 串（带毫秒/微秒），统一显示到秒 */
function formatTime(value?: string | null) {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  return text.length > 19 ? text.slice(0, 19) : text
}

function formatSize(size: number) {
  if (!size) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
</script>
<style scoped>
.kb-page { width: 100%; min-width: 0; }

.page-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #64748b; max-width: 720px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.btn-icon { margin-right: 4px; }

.stat-row { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
}
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.purple { background: #f5f3ff; color: #7c3aed; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }

.kb-body { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 16px; align-items: start; }

.kb-side { background: #fff; border: 1px solid #e6ebf2; border-radius: 12px; padding: 12px; }
.side-head { display: flex; align-items: center; justify-content: space-between; font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 8px; }
.cat-list { display: flex; flex-direction: column; gap: 2px; }
.cat-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 10px;
  border-radius: 8px;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
}
.cat-item:hover { background: #f1f5f9; }
.cat-item.active { background: #eff6ff; color: #1d4ed8; font-weight: 600; }
.cat-count { font-size: 12px; color: #94a3b8; }
.cat-empty { font-size: 12px; color: #94a3b8; padding: 8px 10px; }

.kb-main { background: #fff; border: 1px solid #e6ebf2; border-radius: 12px; padding: 14px 16px; min-width: 0; }
.filter-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.kb-search { width: 320px; }

.doc-title { font-weight: 600; color: #0f172a; }
.cell-sub { margin-top: 3px; font-size: 12px; color: #94a3b8; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.ml-6 { margin-left: 6px; }
.mt-12 { margin-top: 12px; }
.mb-10 { margin-bottom: 10px; }
.full-width { width: 100%; }

.kb-empty { padding: 26px 0; }
.kb-empty-title { font-size: 15px; font-weight: 700; color: #334155; }
.kb-empty-sub { margin-top: 8px; font-size: 13px; color: #94a3b8; line-height: 1.8; }

.kb-upload :deep(.el-upload-dragger) { padding: 22px 0; border-radius: 12px; }
.up-icon { font-size: 34px; color: #94a3b8; }
.up-text { margin-top: 6px; font-size: 13px; color: #475569; }
.up-text em { color: #2563eb; font-style: normal; }
.up-tip { margin-top: 4px; font-size: 12px; color: #94a3b8; }
.up-file {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 13px;
  color: #334155;
}
.up-size { margin-left: auto; color: #94a3b8; font-size: 12px; }

.chunk-tip { font-size: 13px; color: #64748b; line-height: 1.8; margin-bottom: 12px; }
.chunk-list { display: flex; flex-direction: column; gap: 10px; }
.chunk-item { border: 1px solid #e6ebf2; border-radius: 10px; padding: 10px 12px; background: #fcfdff; }
.chunk-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; font-size: 12px; color: #94a3b8; }
.chunk-no { font-weight: 700; color: #2563eb; }
.chunk-model { margin-left: auto; }
.chunk-content { font-size: 13px; color: #334155; line-height: 1.75; white-space: pre-wrap; word-break: break-word; }

.search-tip { font-size: 13px; color: #64748b; line-height: 1.8; margin-bottom: 10px; }
.search-bar { display: flex; gap: 8px; }
.search-meta { margin-top: 10px; font-size: 13px; color: #64748b; }
.search-hint { margin-top: 6px; font-size: 12px; color: #b45309; line-height: 1.7; }
.search-hint code { background: #fff7ed; padding: 0 4px; border-radius: 4px; }
.hit-list { margin-top: 10px; display: flex; flex-direction: column; gap: 10px; max-height: 420px; overflow: auto; }
.hit-item { border: 1px solid #e6ebf2; border-radius: 10px; padding: 10px 12px; }
.hit-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; font-size: 12px; color: #94a3b8; }
.hit-doc { font-weight: 700; color: #0f172a; font-size: 13px; }
.hit-score { margin-left: auto; color: #2563eb; font-weight: 600; }
.hit-content { font-size: 13px; color: #334155; line-height: 1.75; white-space: pre-wrap; word-break: break-word; }
</style>
```

### 6.3 路由

菜单里「知识库」一直有，但路由是空的（点进去什么都没有）。这一篇把它接上。

### 改动：yunti-frontend/src/router/index.ts

改动点：加 /modules/kb 路由。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 86 行附近）**

原来是这样：

``` code-block-container
        },
        {
          path: 'modules/qa',
          name: 'QualityInspection',
```

改成：

``` code-block-container
        },
        {
          path: 'modules/kb',
          name: 'KnowledgeBase',
          component: () => import('../views/kb/index.vue'),
          meta: { title: '企业知识库' },
        },
        {
          path: 'modules/qa',
          name: 'QualityInspection',
```

## 七、运行与验证

### 7.1 后端

``` code-block-container
cd yunti-backend
mvn -pl yunti-customer-service spring-boot:run    # 9093  文档台账、上传、切片预览
mvn -pl yunti-realtime-service spring-boot:run    # 9096  这一篇没改它，但前端要用
mvn -pl yunti-gateway spring-boot:run             # 9090  HTTP 网关

# AI 服务（解析 / 切块 / 向量化 / 检索都在它里面）
cd ../yunti-ai && ./start.sh                       # 9100
```

### 7.2 前端

``` code-block-container
cd yunti-frontend
npm install
npm run dev        # http://localhost:5173
```

### 7.3 手工验证（5 个场景）

**① 上传即索引**

知识库 →「上传文档」→ 拖入 `01-退款与售后政策.md` → 列表里出现这条文档，索引状态是「已索引」，切片数是 3（这一版切块参数下）。

  

<img src="https://article-images.zsxq.com/FsP3Tkw_1iJUxQ9S8R0uaI6U2ou5" class="tiptap-image" alt="图片.png" />

  

<img src="https://article-images.zsxq.com/FpabjYWJc-3hzce9EbogWZkS67Z0" class="tiptap-image" alt="图片.png" />

**② 切片预览**

点切片数列的「3 块」→ 抽屉里能看到每一块的正文、字数、估算 token 数。**切得合不合理在这里一眼就能看出来**。

  

<img src="https://article-images.zsxq.com/Fnr8evZej8gzH0P8Wz_TqRCgz5Rj" class="tiptap-image" alt="图片.png" />

**③ 检索测试**

点「检索测试」→ 输入"退款多久能到账" → 下面列出命中的切片、来自哪份文档、相似度。注意弹框每次打开输入框都是空的。

  

<img src="https://article-images.zsxq.com/Fh8Qb7AUUaspZSqVR4hs0VUQt5yB" class="tiptap-image" alt="图片.png" />

**④ 粘贴正文建文档**

「新建文档」→ 标题填"客服标准话术手册" → 把 `07-客服标准话术手册.txt` 整段贴进正文 → 保存 → 自动切块建索引。再问"客户骂人怎么应对"，应该命中第七章。

  

<img src="https://article-images.zsxq.com/Fr3bWTUMSlfFyxUK0DU4p_6H9TbE" class="tiptap-image" alt="图片.png" />

**⑤ 解析失败要能说清原因**

传一个 xlsx（或扫描件 PDF）→ 列表里这条文档的索引状态是「索引失败」，鼠标悬停能看到"Excel 请先另存为 CSV 再上传"这种能照做的提示，而不是一句"处理失败"。

### 总结

这一篇把企业资料变成了机器人能查的知识资产：

1.  **解析是地基**：企业资料什么格式都有，先把它们统一成纯文本。docx 用 zip+XML、PDF 用 pypdf，都是"用现成的库、别自己造轮子"——手写 PDF 解析在中文 PDF 上必然翻车（我踩过）。

2.  **切块决定检索质量**：递归降级切分 + 块间重叠，是 LlamaIndex `SentenceSplitter` 的思路。切得太碎两边都召回不到，切得太大噪音多。

3.  **LlamaIndex 该用就用**：`SimpleDirectoryReader` / `SentenceSplitter` / `OpenAIEmbedding` 三件套直接解决读文件、切块、向量化。但**存储那一层可以自己管**——用它的能力，不代表要把数据模型交给它。

4.  **工程上要能"退化成跑得起来"**：没装 llama-index 就退回内置实现，服务照常启动；两边都留了开关，部署环境不同也不会起不来。

5.  **状态要落到台账上**：索引成功没成功、切了多少块、失败原因是什么，全都记在 `kb_document` 上。用户看到的是"为什么检索不到"，而不是"点了没反应"。

6.  **每一步都要能看见**：切片预览、检索测试、相似度分数——这三个视图是知识库能不能用的判据。看不见切片效果的知识库，等于没有。

7.  **第三方库的边界要自己摸一遍**：`OpenAIEmbedding` 不认千问的模型名、`DocxReader` 还要额外装 `docx2txt`、`chunk_size` 的单位是 token 而不是字符——这三条都不在首页文档里，全是实测撞出来的。**"装上库"和"这库真能按你想的那样用"之间还有一段距离**，本篇把实测结论都写进来了。
