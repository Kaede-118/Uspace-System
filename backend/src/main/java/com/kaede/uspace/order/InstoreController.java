package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.order.dto.InstoreUserVo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店在店用户接口（用户端）。
 *
 * <p><b>路径归门店、代码归模块 8</b>：数据源是 {@code biz_order}，
 * 而 {@code space} 包不能依赖 {@code order} 包（反方向已存在，加回去就成环），
 * 所以读订单的接口只能落在 order 包。理由与 {@code UserBookingController}
 * 的类注释同源，只是方向相反 —— 那个是「路径归包场、代码归模块 8」。
 *
 * <p><b>需登录，不匿名</b>，这是它与 {@code /api/store} 下另外几个接口的分别：
 * 门店名、营业状态、包场时间表、公告都是<b>店门口的牌子</b>，谁都能看；
 * 而「现在店里有哪些人」是顾客之间才看得见的信息 —— 只有已经成为顾客的人
 * 才谈得上需要它。所以本接口<b>不</b>进 {@code SecurityConfig} 的放行列表，
 * 走默认的「一律要求已认证」。
 *
 * <p><b>响应里没有任何金额</b>：谁在店里、玩了多久是事实，
 * 谁这一单要付多少是个人消费信息。那道边界在 {@link InstoreUserVo} 上。
 */
@RestController
@RequestMapping("/api/store")
public class InstoreController {

    private final InstoreService instoreService;

    public InstoreController(InstoreService instoreService) {
        this.instoreService = instoreService;
    }

    /**
     * 查询门店当前的全部在店顾客。
     *
     * <p>按进店时刻升序 —— 先来的排在前面，与「谁玩了多久」的直觉一致。
     * 没有人在店时返回空数组而不是 404：那是一个完全正常的状态，
     * 前端拿到空数组显示「店里暂时没人」即可。
     *
     * @return 在店顾客名册，<b>不含任何金额</b>
     */
    @GetMapping("/instore")
    public ResponseEntity<ApiResult<List<InstoreUserVo>>> instore() {
        return ApiResult.of(instoreService.listInstoreUsers());
    }
}
