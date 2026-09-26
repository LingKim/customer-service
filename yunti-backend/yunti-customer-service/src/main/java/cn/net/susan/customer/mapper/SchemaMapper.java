package cn.net.susan.customer.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 读库里的元数据（有哪些表、哪些列），给启动自检用。
 */
@Mapper
public interface SchemaMapper {

    /** 当前库 public schema 下所有表名 */
    List<String> selectTables();

    /** 当前库 public schema 下所有 "表名.列名" */
    List<String> selectColumns();
}
