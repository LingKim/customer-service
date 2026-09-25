package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.Customer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * customer 客户 Mapper。
 */
@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
}
