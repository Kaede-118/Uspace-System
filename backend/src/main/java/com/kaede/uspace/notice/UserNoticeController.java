package com.kaede.uspace.notice;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.kaede.uspace.notice.dto.NoticeVo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;


/**
 * 用户端公告接口。
 *
 * <p>路径挂在 {@code /api/store} 下而不是 {@code /api/notices}：公告属于<b>门店</b>，
 * 与 {@code GET /api/store/status}（营业状态）是同一类「店门口的告示牌」。
 * 将来开分店时，这个路径自然带上 {@code storeId} 的含义。
 *
 * <p><b>本接口在 {@code SecurityConfig.PUBLIC_PATHS} 里，匿名可访问</b>，
 * 理由与 {@code GET /api/store/status} 的既有注释完全对齐：
 * 「门店名称、地址与『此刻是否营业』是面向公众的信息，相当于店门口挂的牌子」——
 * <b>店门口的告示牌不需要注册才能看</b>。
 *
 * <p>而且未注册的用户恰恰更需要它：看到「今晚 19:00–21:00 已被包场」才知道要错峰，
 * 这正是公告存在的意义。要求先注册再看，等于让第一次来的人白跑一趟。
 *
 * <p><b>不提供公告详情接口</b>：列表的每条已经带着完整正文，
 * 前端点开时直接用本地那份数据即可。为一条几十字的公告多开一个端点，
 * 只会多一个需要维护的鉴权与错误码分支。
 */
@RestController
@RequestMapping("/api/store/notices")
@Validated
public class UserNoticeController {

    private final NoticeService noticeService;

    public UserNoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    /**
     * 分页查询门店公告。
     *
     * <p><b>置顶的在最前</b>，其余按发布先后倒序（最新的在最上面），
     * <b>没有时间窗</b> —— 公告是消息流，发出来就是可见的，
     * 不存在「到点才显示」或「过期自动隐藏」。
     *
     * <p>首页公告栏与「全部公告」页共用这一个接口：
     * 首页传 {@code size=4}，全部页翻页往下看。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，上限见 {@code NoticeService#MAX_USER_PAGE_SIZE}
     * @return 分页结果，没有时 {@code records} 为空列表
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<NoticeVo>>> list(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "4")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = NoticeService.MAX_USER_PAGE_SIZE, message = "每页最多 20 条") long size) {
        return ApiResult.of(noticeService.listForUser(page, size));
    }
}
