package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.CustomerEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * customer_event 客户动态 Mapper。
 */
@Mapper
public interface CustomerEventMapper extends BaseMapper<CustomerEvent> {
}
