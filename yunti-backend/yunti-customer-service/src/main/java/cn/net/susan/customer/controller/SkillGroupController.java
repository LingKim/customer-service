package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.SkillGroupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 技能组：管理组、组内坐席、以及"渠道绑定哪个组"。
 */
@RestController
@RequestMapping("/api/customer/skill-groups")
public class SkillGroupController {

    private final SkillGroupService skillGroupService;
    private final JwtTokenParser jwtTokenParser;

    public SkillGroupController(SkillGroupService skillGroupService, JwtTokenParser jwtTokenParser) {
        this.skillGroupService = skillGroupService;
        this.jwtTokenParser = jwtTokenParser;
    }

    @GetMapping
    public ApiResponse<List<SkillGroupService.GroupVO>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(skillGroupService.list(jwtTokenParser.requireLoginUser(authorization)));
    }

    @PostMapping
    public ApiResponse<SkillGroupService.GroupVO> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(skillGroupService.create(
                user, body.name(), body.description(), body.overflowAfterSeconds()));
    }

    @PutMapping("/{groupId}")
    public ApiResponse<SkillGroupService.GroupVO> update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long groupId,
            @Valid @RequestBody UpdateBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(skillGroupService.update(
                user, groupId, body.description(), body.overflowAfterSeconds()));
    }

    /**
     * 设置组内坐席（覆盖式：前端传最终想要的成员列表）。
     */
    @PutMapping("/{groupId}/members")
    public ApiResponse<SkillGroupService.GroupVO> setMembers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long groupId,
            @RequestBody MemberBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(skillGroupService.setMembers(user, groupId, body.userIds()));
    }

    /**
     * 渠道绑定技能组（skillGroupId 传 null 表示解绑）。
     */
    @PostMapping("/bind-channel")
    public ApiResponse<Void> bindChannel(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody BindBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        skillGroupService.bindChannel(user, body.channelId(), body.skillGroupId());
        return ApiResponse.ok(null);
    }

    /**
     * 删除技能组（默认组不允许删）。
     */
    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> remove(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long groupId
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        skillGroupService.remove(user, groupId);
        return ApiResponse.ok(null);
    }

    /** 新建技能组请求体 */
    public record CreateBody(
            @NotBlank(message = "请填写技能组名称")
            @Size(max = 64)
            String name,

            @Size(max = 255)
            String description,

            Integer overflowAfterSeconds
    ) {
    }

    /** 修改技能组请求体 */
    public record UpdateBody(
            @Size(max = 255)
            String description,

            Integer overflowAfterSeconds
    ) {
    }

    /** 设置成员请求体 */
    public record MemberBody(List<Long> userIds) {
    }

    /** 渠道绑定请求体 */
    public record BindBody(Long channelId, Long skillGroupId) {
    }
}
