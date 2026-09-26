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
            try (InputStream in = objectStorage.download(meta.getObjectKey()).getInputStream()) {
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
