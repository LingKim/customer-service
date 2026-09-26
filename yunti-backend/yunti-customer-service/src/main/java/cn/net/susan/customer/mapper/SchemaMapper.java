package cn.net.susan.customer.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/**
 * 读库里的元数据（有哪些表、哪些列、列有多宽），给启动自检用。
 */
@Mapper
public interface SchemaMapper {

    /** 当前库 public schema 下所有表名 */
    List<String> selectTables();

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
