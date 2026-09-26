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
