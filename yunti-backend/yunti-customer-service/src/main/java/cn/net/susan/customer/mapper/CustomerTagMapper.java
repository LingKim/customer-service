package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.CustomerTag;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * customer_tag 客户标签关联 Mapper。
 */
@Mapper
public interface CustomerTagMapper extends BaseMapper<CustomerTag> {

    /**
     * 批量取一批客户身上的标签（客户列表一页 10~50 条，一次查完）。
     *
     * <p>返回 Map 而不是实体：列表要的是"标签名 + 分组 + 颜色"，
     * 颜色只在 customer_tag_def 里有，所以要连一次定义表。</p>
     */
    List<Map<String, Object>> selectTagsOfCustomers(
            @Param("tenantCode") String tenantCode,
            @Param("customerIds") List<Long> customerIds
    );

    /**
     * 按标签找客户（标签筛选用的子查询拿到的是 customer_id，交给主查询做 IN）。
     */
    List<Long> selectCustomerIdsByTag(
            @Param("tenantCode") String tenantCode,
            @Param("tagId") Long tagId
    );
}
