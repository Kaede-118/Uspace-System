package com.kaede.uspace.access;

import com.kaede.uspace.access.dto.AccessRecordVo;
import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开门记录接口（模块 6 的用户端）。
 *
 * <p>类上没有 {@code @PreAuthorize} —— 登录用户即可访问自己的记录，
 * 但只能访问自己的：{@code /me} 这个路径里不出现用户 ID，身份一律从凭证里取，
 * <b>从根上就没有「改一下 URL 里的 ID 就能看别人开门记录」这个入口</b>。
 *
 * <p>本模块尚无「离场记录」可查 —— 出门不产生门锁记录（见 {@code AccessRecord} 的类注释），
 * 离场时刻在订单上。等模块 8 落地后，用户端的「我的使用记录」会以订单为主、本接口为辅。
 */
@RestController
@RequestMapping("/api/access-records")
public class UserAccessController {

    private final AccessRecordService accessRecordService;

    /**
     * 构造方法。
     *
     * @param accessRecordService 开门记录服务
     */
    public UserAccessController(AccessRecordService accessRecordService) {
        this.accessRecordService = accessRecordService;
    }

    /**
     * 分页查询我自己的开门记录。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数（分页插件会按单页上限截断）
     * @param me   当前登录用户，由认证过滤器从凭证解析后注入
     * @return 分页的开门记录
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResult<PageResult<AccessRecordVo>>> myRecords(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(accessRecordService.listMyRecords(me.id(), page, size));
    }
}
