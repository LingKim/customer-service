package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Channel;
import cn.net.susan.customer.entity.ChannelKey;
import cn.net.susan.customer.entity.SkillGroup;
import cn.net.susan.customer.internal.UserNameClient;
import cn.net.susan.customer.mapper.ChannelKeyMapper;
import cn.net.susan.customer.mapper.ChannelMapper;
import cn.net.susan.customer.mapper.SkillGroupMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChannelOnboardingService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private final ChannelMapper channelMapper;
    private final ChannelKeyMapper keyMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final UserNameClient userNameClient;

    public ChannelOnboardingService(ChannelMapper channelMapper, ChannelKeyMapper keyMapper,
                                    SkillGroupMapper skillGroupMapper, SnowflakeIdGenerator idGenerator,
                                    UserNameClient userNameClient) {
        this.channelMapper = channelMapper;
        this.keyMapper = keyMapper;
        this.skillGroupMapper = skillGroupMapper;
        this.idGenerator = idGenerator;
        this.userNameClient = userNameClient;
    }

    @Transactional
    public ChannelResult create(LoginUser user, int channelType, String name, String description) {
        String tenantCode = tenantOf(user);
        if (channelType < 1 || channelType > 3) {
            throw new BizException(40001, "当前仅支持网站、微信公众号和微信小程序渠道");
        }
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BizException(40001, "渠道名称不能为空且不得超过 64 字");
        }
        SkillGroup group = ensureDefaultGroup(tenantCode, user.userId());
        Channel channel = Channel.builder()
                .id(idGenerator.nextId()).tenantCode(tenantCode)
                .channelId("CH" + UUID.randomUUID().toString().replace("-", "").substring(0, 30))
                .channelType(channelType).name(name.trim())
                .desc(description == null || description.isBlank() ? null : description.trim())
                .skillGroupId(group.getId()).status(1).stage(3).isEnabled(true)
                .creator(String.valueOf(user.userId())).deleted(false).build();
        channelMapper.insert(channel);
        String secret = randomKey();
        keyMapper.insert(newKey(channel, user.userId(), secret));
        return toResult(channel, user.name(), mask(secret), secret);
    }

    @Transactional(readOnly = true)
    public List<ChannelResult> list(LoginUser user, String authorization) {
        String tenantCode = tenantOf(user);
        List<Channel> channels = channelMapper.selectList(Wrappers.<Channel>lambdaQuery()
                .eq(Channel::getTenantCode, tenantCode).eq(Channel::getDeleted, false)
                .orderByDesc(Channel::getCreateTime).orderByDesc(Channel::getId));
        Map<String, String> names = userNameClient.namesOf(channels.stream().map(Channel::getCreator).toList(), authorization);
        return channels.stream().map(channel -> {
            ChannelKey key = activeKey(channel.getId(), tenantCode);
            return toResult(channel, names.getOrDefault(channel.getCreator(), channel.getCreator()),
                    key == null ? null : mask(key.getAppKey()), null);
        }).toList();
    }

    @Transactional(readOnly = true)
    public String revealKey(LoginUser user, long channelId) {
        String tenantCode = tenantOf(user);
        requireOwned(channelId, tenantCode);
        ChannelKey key = activeKey(channelId, tenantCode);
        if (key == null) throw new BizException(40401, "渠道密钥不存在");
        return key.getAppKey();
    }

    @Transactional
    public ChannelResult rotateKey(LoginUser user, long channelId) {
        String tenantCode = tenantOf(user);
        Channel channel = requireOwnedForUpdate(channelId, tenantCode);
        ChannelKey oldKey = activeKey(channelId, tenantCode);
        if (oldKey != null) {
            oldKey.setStatus(2);
            oldKey.setRotatedAt(LocalDateTime.now());
            oldKey.setEditor(String.valueOf(user.userId()));
            oldKey.setUpdateTime(LocalDateTime.now());
            keyMapper.updateById(oldKey);
        }
        String secret = randomKey();
        keyMapper.insert(newKey(channel, user.userId(), secret));
        return toResult(channel, user.name(), mask(secret), secret);
    }

    @Transactional
    public ChannelResult updateStatus(LoginUser user, long channelId, boolean enabled) {
        String tenantCode = tenantOf(user);
        Channel channel = requireOwnedForUpdate(channelId, tenantCode);
        channel.setStatus(enabled ? 1 : 2);
        channel.setIsEnabled(enabled);
        channel.setEditor(String.valueOf(user.userId()));
        channel.setUpdateTime(LocalDateTime.now());
        channelMapper.updateById(channel);
        ChannelKey key = activeKey(channelId, tenantCode);
        return toResult(channel, user.name(), key == null ? null : mask(key.getAppKey()), null);
    }

    private SkillGroup ensureDefaultGroup(String tenantCode, long userId) {
        SkillGroup group = skillGroupMapper.selectOne(Wrappers.<SkillGroup>lambdaQuery()
                .eq(SkillGroup::getTenantCode, tenantCode).eq(SkillGroup::getIsDefault, true)
                .eq(SkillGroup::getDeleted, false).last("LIMIT 1"));
        if (group != null) return group;
        SkillGroup created = SkillGroup.builder().id(idGenerator.nextId()).tenantCode(tenantCode)
                .name("默认技能组").description("系统默认技能组")
                .isDefault(true).isEnabled(true).creator(String.valueOf(userId)).deleted(false).build();
        skillGroupMapper.insert(created);
        return created;
    }

    private Channel requireOwned(long channelId, String tenantCode) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || Boolean.TRUE.equals(channel.getDeleted()) || !tenantCode.equals(channel.getTenantCode())) {
            throw new BizException(40401, "渠道不存在");
        }
        return channel;
    }

    private Channel requireOwnedForUpdate(long channelId, String tenantCode) {
        Channel channel = channelMapper.findOwnedForUpdate(channelId, tenantCode);
        if (channel == null) throw new BizException(40401, "渠道不存在");
        return channel;
    }

    private ChannelKey activeKey(long channelId, String tenantCode) {
        return keyMapper.selectOne(Wrappers.<ChannelKey>lambdaQuery()
                .eq(ChannelKey::getChannelId, channelId).eq(ChannelKey::getTenantCode, tenantCode)
                .eq(ChannelKey::getStatus, 1).eq(ChannelKey::getDeleted, false).last("LIMIT 1"));
    }

    private ChannelKey newKey(Channel channel, long userId, String secret) {
        return ChannelKey.builder().id(idGenerator.nextId()).tenantCode(channel.getTenantCode())
                .channelId(channel.getId()).appKey(secret).keyType(1).status(1)
                .creator(String.valueOf(userId)).deleted(false).build();
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2 || user.tenantCode() == null
                || user.tenantCode().isBlank() || "PLATFORM".equals(user.tenantCode())) {
            throw new BizException(40301, "请先完成企业开通并重新登录");
        }
        return user.tenantCode();
    }

    private String randomKey() {
        StringBuilder result = new StringBuilder(32);
        for (int i = 0; i < 32; i++) result.append(KEY_ALPHABET.charAt(RANDOM.nextInt(KEY_ALPHABET.length())));
        return result.toString();
    }

    private String mask(String secret) {
        return secret.substring(0, 6) + "**********************" + secret.substring(secret.length() - 4);
    }

    private ChannelResult toResult(Channel channel, String creatorName, String maskedKey, String appKey) {
        return new ChannelResult(String.valueOf(channel.getId()), channel.getChannelId(),
                channel.getChannelType(), channel.getName(), channel.getDesc(), channel.getStatus(),
                channel.getStage(), maskedKey, appKey, creatorName, channel.getCreateTime());
    }

    public record ChannelResult(String id, String channelId, int channelType, String name, String desc,
                                int status, int stage, String maskedKey, String appKey,
                                String creatorName, LocalDateTime createTime) {}
}
