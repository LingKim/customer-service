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

    /** 知识问答：让 AI 查资料后回答并标出处 */
    @PostMapping("/ask")
    public ApiResponse<KbService.AskResult> ask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody AskBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(kbService.ask(user, body.question(), body.topK()));
    }

    /** 知识问答请求体 */
    public record AskBody(
            @NotBlank(message = "请输入要问的问题")
            @Size(max = 500)
            String question,

            @Min(value = 1, message = "最多参考 1~20 条资料")
            @Max(value = 20, message = "最多参考 1~20 条资料")
            Integer topK
    ) {
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
