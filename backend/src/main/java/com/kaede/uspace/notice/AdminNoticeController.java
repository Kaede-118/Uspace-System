package com.kaede.uspace.notice;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.notice.dto.AdminNoticeVo;
import com.kaede.uspace.notice.dto.CreateNoticeRequest;
import com.kaede.uspace.notice.dto.UpdateNoticeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公告管理接口（管理员侧）。
 *
 * <p>路径前缀 {@code /api/admin}，与用户端的 {@code /api/store/notices} 分开。
 *
 * <p><b>权限声明放在类上而不是每个方法上</b>：本类每个接口都要求管理员，
 * 逐个方法写 {@code @PreAuthorize} 只是重复，且新增方法时容易漏 ——
 * 漏掉的后果是接口裸奔且没有任何报错提醒。理由详见 {@code AdminUserController}。
 *
 * <p><b>列表要分页，与机台列表不分页相反</b>：机台是「店里有几台机器」，
 * 十几到几十条封顶；而公告是<b>只增不改的消息流</b>，机台每变一次状况就多一条，
 * 会持续累积。不分页的话，运营几个月后打开列表要在几百条里翻。
 *
 * <p><b>能改的只有手写公告</b>：自动公告走 {@code PUT} / {@code DELETE}
 * 会返回 40929。这不是权限问题，而是「它是已经发生的事实的记录，
 * 改写或删除等于篡改历史」—— 详见 {@code NoticeService#updateManual} 的注释。
 */
@RestController
@RequestMapping("/api/admin/notices")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminNoticeController {

    private final NoticeService noticeService;

    public AdminNoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    /**
     * 分页查询公告。
     *
     * @param page        页码，从 1 开始
     * @param size        每页条数，最多 100
     * @param publishMode 发布方式筛选：{@code AUTO} / {@code MANUAL}，可空
     * @return 公告分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<AdminNoticeVo>>> list(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @RequestParam(required = false) String publishMode) {
        return ApiResult.of(noticeService.listForAdmin(page, size, publishMode));
    }

    /**
     * 发布一条手写公告。
     *
     * <p>请求体里<b>没有</b> {@code publishMode} —— 走到这个接口就是手写，
     * 后端硬编码，不让调用方声明（多一个字段就多一个能被传错的东西）。
     *
     * <p><b>也没有生效时段</b>：公告是消息流，发出来就是可见的，
     * 不存在「到点才显示、过期自动隐藏」。想提前准备就写到点再发。
     *
     * @param request 标题与正文
     * @param me      当前登录的管理员
     * @return 新建的公告
     */
    @PostMapping
    public ResponseEntity<ApiResult<AdminNoticeVo>> create(
            @Valid @RequestBody CreateNoticeRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(noticeService.createManual(me.id(), request));
    }

    /**
     * 修改一条手写公告。
     *
     * <p><b>自动公告返回 40929</b>：它是已经发生的事实的记录，改写等于篡改历史。
     * 消息流里也没有「下架」这个动作 —— 后面还会有新的消息盖过它。
     *
     * @param id      公告 ID
     * @param request 新的标题与正文
     * @return 更新后的公告
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<AdminNoticeVo>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateNoticeRequest request) {
        return ApiResult.of(noticeService.updateManual(id, request));
    }

    /**
     * 下架一条手写公告（逻辑删除）。
     *
     * <p>同理，自动公告返回 40929 —— 它是已经发生的事实的记录，
     * 删掉等于篡改历史，而它本就没有「下架」这个动作。
     * 反过来手写公告可以删：它不是已发生的事实，而是运营现在想说的话，
     * 说错了、过期了、想换个说法，都该能收回来。
     *
     * @param id 公告 ID
     * @return 成功时 data 为 null
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<Void>> delete(@PathVariable Long id) {
        return ApiResult.of(noticeService.deleteManual(id));
    }
}
