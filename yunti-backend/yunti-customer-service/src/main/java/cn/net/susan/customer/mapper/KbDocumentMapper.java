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
