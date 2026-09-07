package cn.net.susan.tenant.service;

import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.tenant.entity.Enterprise;
import cn.net.susan.tenant.mapper.EnterpriseMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业草稿服务（注册流程：user-service 创建账号后调用本服务落企业资料）。
 */
@Service
public class EnterpriseDraftService {

    /** 企业草稿状态：1-待审核 */
    private static final int STATUS_PENDING = 1;

    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 同日 4 位序号（进程内自增；多实例时由雪花低位/分布式发号兜底） */
    private static final AtomicInteger DAY_SEQ = new AtomicInteger(0);

    private final EnterpriseMapper enterpriseMapper;
    private final SnowflakeIdGenerator idGenerator;

    public EnterpriseDraftService(EnterpriseMapper enterpriseMapper, SnowflakeIdGenerator idGenerator) {
        this.enterpriseMapper = enterpriseMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 创建企业草稿，返回企业编码。
     */
    @Transactional
    public DraftResult createDraft(DraftCommand command) {
        long id = idGenerator.nextId();
        String enterpriseCode = generateEnterpriseCode();
        Enterprise enterprise = Enterprise.builder()
                .id(id)
                .enterpriseCode(enterpriseCode)
                .tenantCode("")
                .companyName(command.companyName())
                .industry(command.industry())
                .scale(command.scale())
                .contactName(command.contactName())
                .contactPhone(command.contactPhone())
                .contactEmail(command.contactEmail())
                .status(STATUS_PENDING)
                .creator(String.valueOf(command.applicantId()))
                .deleted(false)
                .build();
        enterpriseMapper.insert(enterprise);
        return new DraftResult(id, enterpriseCode, STATUS_PENDING);
    }

    /**
     * 逻辑删除企业草稿（注册补偿）。
     */
    @Transactional
    public void softDeleteByEnterpriseCode(String enterpriseCode) {
        LambdaUpdateWrapper<Enterprise> update = Wrappers.lambdaUpdate(Enterprise.class)
                .eq(Enterprise::getEnterpriseCode, enterpriseCode)
                .set(Enterprise::getDeleted, true)
                .set(Enterprise::getUpdateTime, LocalDateTime.now());
        enterpriseMapper.update(null, update);
    }

    /**
     * 企业编码：E + 8 位日期 + 7 位序号，定长 16 位（对应数据库 CHECK 约束）。
     */
    private String generateEnterpriseCode() {
        String date = CODE_DATE.format(LocalDate.now());
        String prefix = "E" + date;
        String latest = enterpriseMapper.findLatestCodeByDatePrefix(prefix);
        long baseSeq = latest == null ? 0 : parseSeq(latest, prefix);
        long seq = Math.max(baseSeq + 1, DAY_SEQ.incrementAndGet());
        return prefix + String.format("%07d", seq);
    }

    private long parseSeq(String code, String prefix) {
        try {
            return Long.parseLong(code.substring(prefix.length()));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 创建草稿命令。
     */
    public record DraftCommand(
            long applicantId,
            String companyName,
            String industry,
            String scale,
            String contactName,
            String contactPhone,
            String contactEmail
    ) {
    }

    /**
     * 创建结果。
     */
    public record DraftResult(long enterpriseId, String enterpriseCode, int status) {
    }
}
