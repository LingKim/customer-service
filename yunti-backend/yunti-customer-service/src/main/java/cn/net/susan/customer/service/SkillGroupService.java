package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Channel;
import cn.net.susan.customer.entity.SkillGroup;
import cn.net.susan.customer.entity.SkillGroupMember;
import cn.net.susan.customer.internal.UserNameClient;
import cn.net.susan.customer.mapper.ChannelMapper;
import cn.net.susan.customer.mapper.SkillGroupMapper;
import cn.net.susan.customer.mapper.SkillGroupMemberMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 技能组：把坐席分组（售前 / 售后 / VIP），渠道绑定到组之后，
 * 这个渠道进来的会话只找组内坐席，找不到人再按排队策略升级。
 */
@Service
public class SkillGroupService {

    private static final int MEMBER_IN_GROUP = 1;
    private static final int MEMBER_REMOVED = 2;

    private final SkillGroupMapper skillGroupMapper;
    private final SkillGroupMemberMapper memberMapper;
    private final ChannelMapper channelMapper;
    private final UserNameClient userNameClient;
    private final SnowflakeIdGenerator idGenerator;

    public SkillGroupService(
            SkillGroupMapper skillGroupMapper,
            SkillGroupMemberMapper memberMapper,
            ChannelMapper channelMapper,
            UserNameClient userNameClient,
            SnowflakeIdGenerator idGenerator
    ) {
        this.skillGroupMapper = skillGroupMapper;
        this.memberMapper = memberMapper;
        this.channelMapper = channelMapper;
        this.userNameClient = userNameClient;
        this.idGenerator = idGenerator;
    }

    /** 技能组列表（带成员与绑定的渠道） */
    public List<GroupVO> list(LoginUser user) {
        String tenant = tenantOf(user);
        List<SkillGroup> groups = skillGroupMapper.selectList(Wrappers.<SkillGroup>lambdaQuery()
                .eq(SkillGroup::getTenantCode, tenant)
                .eq(SkillGroup::getDeleted, false)
                .orderByAsc(SkillGroup::getCreateTime));
        Map<Long, List<SkillGroupMember>> members = membersOf(tenant);
        List<Channel> channels = channelMapper.selectList(Wrappers.<Channel>lambdaQuery()
                .eq(Channel::getTenantCode, tenant)
                .eq(Channel::getDeleted, false));
        return groups.stream().map(group -> toVO(group, members.get(group.getId()), channels)).toList();
    }

    /** 新建技能组 */
    @Transactional
    public GroupVO create(LoginUser user, String name, String description, Integer overflowAfterSeconds) {
        String tenant = tenantOf(user);
        if (name == null || name.isBlank()) {
            throw new BizException(40001, "请填写技能组名称");
        }
        Long exists = skillGroupMapper.selectCount(Wrappers.<SkillGroup>lambdaQuery()
                .eq(SkillGroup::getTenantCode, tenant)
                .eq(SkillGroup::getName, name.trim())
                .eq(SkillGroup::getDeleted, false));
        if (exists != null && exists > 0) {
            throw new BizException(40001, "同名技能组已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        SkillGroup group = SkillGroup.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .name(name.trim())
                .description(description == null ? null : description.trim())
                .isDefault(false)
                .isEnabled(true)
                .overflowAfterSeconds(normalizeOverflow(overflowAfterSeconds))
                .createTime(now)
                .updateTime(now)
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        skillGroupMapper.insert(group);
        return toVO(group, List.of(), List.of());
    }

    /** 修改技能组的说明与排队升级时长 */
    @Transactional
    public GroupVO update(LoginUser user, long groupId, String description, Integer overflowAfterSeconds) {
        String tenant = tenantOf(user);
        SkillGroup group = requireGroup(tenant, groupId);
        SkillGroup update = new SkillGroup();
        update.setId(group.getId());
        update.setUpdateTime(LocalDateTime.now());
        if (description != null) {
            update.setDescription(description.trim());
        }
        if (overflowAfterSeconds != null) {
            update.setOverflowAfterSeconds(normalizeOverflow(overflowAfterSeconds));
        }
        skillGroupMapper.updateById(update);
        return list(user).stream().filter(item -> item.id().equals(String.valueOf(groupId))).findFirst()
                .orElseThrow(() -> new BizException(40401, "技能组不存在"));
    }

    /** 覆盖式设置成员：前端传最终想要的坐席 ID 列表 */
    @Transactional
    public GroupVO setMembers(LoginUser user, long groupId, List<Long> userIds) {
        String tenant = tenantOf(user);
        SkillGroup group = requireGroup(tenant, groupId);
        List<SkillGroupMember> existing = memberMapper.selectList(Wrappers.<SkillGroupMember>lambdaQuery()
                .eq(SkillGroupMember::getTenantCode, tenant)
                .eq(SkillGroupMember::getSkillGroupId, group.getId())
                .eq(SkillGroupMember::getDeleted, false));
        List<Long> wanted = userIds == null ? List.of() : userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        LocalDateTime now = LocalDateTime.now();
        for (SkillGroupMember member : existing) {
            boolean keep = wanted.contains(member.getUserId());
            int target = keep ? MEMBER_IN_GROUP : MEMBER_REMOVED;
            if (!Integer.valueOf(target).equals(member.getStatus())) {
                SkillGroupMember update = new SkillGroupMember();
                update.setId(member.getId());
                update.setStatus(target);
                update.setUpdateTime(now);
                memberMapper.updateById(update);
            }
        }
        for (Long userId : wanted) {
            boolean already = existing.stream().anyMatch(item -> item.getUserId().equals(userId));
            if (already) {
                continue;
            }
            memberMapper.insert(SkillGroupMember.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .skillGroupId(group.getId())
                    .userId(userId)
                    .isLeader(false)
                    .status(MEMBER_IN_GROUP)
                    .createTime(now)
                    .updateTime(now)
                    .creator(String.valueOf(user.userId()))
                    .deleted(false)
                    .build());
        }
        return setMembersResult(tenant, group, userIds);
    }

    /** 渠道绑定技能组 */
    @Transactional
    public void bindChannel(LoginUser user, long channelId, Long groupId) {
        String tenant = tenantOf(user);
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || !tenant.equals(channel.getTenantCode()) || Boolean.TRUE.equals(channel.getDeleted())) {
            throw new BizException(40401, "渠道不存在");
        }
        if (groupId != null) {
            requireGroup(tenant, groupId);
        }
        channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                .eq(Channel::getId, channel.getId())
                .eq(Channel::getTenantCode, tenant)
                .eq(Channel::getDeleted, false)
                .set(Channel::getSkillGroupId, groupId)
                .set(Channel::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * 删除技能组：软删组与成员关系，并把绑在这个组上的渠道解绑。
     */
    @Transactional
    public void remove(LoginUser user, long groupId) {
        String tenant = tenantOf(user);
        SkillGroup group = requireGroup(tenant, groupId);
        if (Boolean.TRUE.equals(group.getIsDefault())) {
            throw new BizException(40001, "默认技能组不能删除");
        }
        LocalDateTime now = LocalDateTime.now();
        SkillGroup update = new SkillGroup();
        update.setId(group.getId());
        update.setDeleted(true);
        // 名称上有唯一约束 (tenant_code, name)，删除时把名字让出来，否则同名建不回来
        update.setName(group.getName() + "#deleted-" + group.getId());
        update.setUpdateTime(now);
        skillGroupMapper.updateById(update);

        List<SkillGroupMember> members = memberMapper.selectList(Wrappers.<SkillGroupMember>lambdaQuery()
                .eq(SkillGroupMember::getTenantCode, tenant)
                .eq(SkillGroupMember::getSkillGroupId, group.getId())
                .eq(SkillGroupMember::getDeleted, false));
        for (SkillGroupMember member : members) {
            SkillGroupMember memberUpdate = new SkillGroupMember();
            memberUpdate.setId(member.getId());
            memberUpdate.setDeleted(true);
            memberUpdate.setUpdateTime(now);
            memberMapper.updateById(memberUpdate);
        }

        List<Channel> bound = channelMapper.selectList(Wrappers.<Channel>lambdaQuery()
                .eq(Channel::getTenantCode, tenant)
                .eq(Channel::getSkillGroupId, group.getId())
                .eq(Channel::getDeleted, false));
        for (Channel channel : bound) {
            channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                    .eq(Channel::getId, channel.getId())
                    .eq(Channel::getTenantCode, tenant)
                    .eq(Channel::getDeleted, false)
                    .set(Channel::getSkillGroupId, null)
                    .set(Channel::getUpdateTime, now));
        }
    }

    private GroupVO setMembersResult(String tenant, SkillGroup group, List<Long> userIds) {
        List<SkillGroupMember> members = memberMapper.selectList(Wrappers.<SkillGroupMember>lambdaQuery()
                .eq(SkillGroupMember::getTenantCode, tenant)
                .eq(SkillGroupMember::getSkillGroupId, group.getId())
                .eq(SkillGroupMember::getStatus, MEMBER_IN_GROUP)
                .eq(SkillGroupMember::getDeleted, false));
        List<Channel> channels = channelMapper.selectList(Wrappers.<Channel>lambdaQuery()
                .eq(Channel::getTenantCode, tenant)
                .eq(Channel::getSkillGroupId, group.getId())
                .eq(Channel::getDeleted, false));
        return toVO(group, members, channels);
    }

    private GroupVO toVO(SkillGroup group, List<SkillGroupMember> members, List<Channel> channels) {
        List<SkillGroupMember> memberList = members == null ? List.of() : members.stream()
                .filter(item -> Integer.valueOf(MEMBER_IN_GROUP).equals(item.getStatus()))
                .toList();
        List<String> userIds = memberList.stream()
                .map(item -> String.valueOf(item.getUserId()))
                .toList();
        Map<String, String> names = userNameClient.namesOf(userIds);
        List<MemberVO> memberVOs = userIds.stream()
                .map(id -> new MemberVO(id, names.getOrDefault(id, "客服" + id)))
                .toList();
        // 只算真正绑到这个组上的渠道，别把租户里所有渠道都挂上去
        List<ChannelVO> channelVOs = (channels == null ? List.<Channel>of() : channels).stream()
                .filter(channel -> group.getId().equals(channel.getSkillGroupId()))
                .map(channel -> new ChannelVO(String.valueOf(channel.getId()), channel.getName()))
                .toList();
        return new GroupVO(
                String.valueOf(group.getId()),
                group.getName(),
                group.getDescription(),
                Boolean.TRUE.equals(group.getIsDefault()),
                group.getOverflowAfterSeconds() == null ? 60 : group.getOverflowAfterSeconds(),
                memberVOs,
                channelVOs);
    }

    private Map<Long, List<SkillGroupMember>> membersOf(String tenant) {
        List<SkillGroupMember> all = memberMapper.selectList(Wrappers.<SkillGroupMember>lambdaQuery()
                .eq(SkillGroupMember::getTenantCode, tenant)
                .eq(SkillGroupMember::getDeleted, false));
        return all.stream().collect(java.util.stream.Collectors.groupingBy(SkillGroupMember::getSkillGroupId));
    }

    private SkillGroup requireGroup(String tenant, long groupId) {
        SkillGroup group = skillGroupMapper.selectById(groupId);
        if (group == null || !tenant.equals(group.getTenantCode()) || Boolean.TRUE.equals(group.getDeleted())) {
            throw new BizException(40401, "技能组不存在");
        }
        return group;
    }

    private int normalizeOverflow(Integer seconds) {
        if (seconds == null) {
            return 60;
        }
        if (seconds < 0 || seconds > 3600) {
            throw new BizException(40001, "排队升级时长应在 0~3600 秒之间（0 表示不升级）");
        }
        return seconds;
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用在线客服");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用在线客服");
        }
        return tenant;
    }

    /** 技能组视图 */
    public record GroupVO(
            String id,
            String name,
            String description,
            boolean isDefault,
            int overflowAfterSeconds,
            List<MemberVO> members,
            List<ChannelVO> channels
    ) {
    }

    /** 组内坐席 */
    public record MemberVO(String userId, String name) {
    }

    /** 绑定到这个组的渠道 */
    public record ChannelVO(String id, String name) {
    }
}
