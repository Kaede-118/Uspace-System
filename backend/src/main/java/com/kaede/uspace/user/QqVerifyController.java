package com.kaede.uspace.user;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.user.dto.QqVerifyIssueRequest;
import com.kaede.uspace.user.dto.QqVerifyIssueVo;
import com.kaede.uspace.user.dto.QqVerifyStatusRequest;
import com.kaede.uspace.user.dto.QqVerifyStatusVo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * QQ 号验证接口（模块 1）。
 *
 * <p>注册时「填了 QQ 就必须先证明这个号是本人的」。证明的办法是一次
 * <b>群内往返</b>，本类提供它在 Web 侧的两端：
 *
 * <pre>
 * ① 用户在注册页填 QQ，点「获取验证码」
 *       POST /api/user/qq-verify          → { challengeId, code }
 * ② 他把那 6 位数字发到 QQ 群
 *       （群里那一半由模块 11 的 QqBroadcastListener / QqCommandService 承担：
 *        机器人看到消息后调 QqVerifyService#confirm 回执给后端）
 * ③ 页面轮询
 *       POST /api/user/qq-verify/status   → { verified }
 * ④ 提交注册时带上 challengeId
 *       POST /api/user/register           → UserService#register 里 consume
 * </pre>
 *
 * <p>⚠️ <b>这两个接口都是匿名的</b>（注册之前用户没有任何凭证），
 * 因此必须进 {@code SecurityConfig.PUBLIC_PATHS}。它们的安全性由三样东西承担：
 * 签发有数量上限、验证码本身不是凭证（群里谁都能看见，但只有持有那个 QQ 的人
 * 查得到自己那条记录）、以及 {@code challengeId} 不进 URL。
 * 详见 {@link QqVerifyService} 的类注释。
 *
 * <p>⚠️ <b>「查状态」是纯查询，但仍然做成了 POST</b> —— 因为它要带
 * {@code challengeId}，而那是个能换取账号绑定的凭证。放进 URL 查询参数会落进
 * 访问日志与浏览器历史，看到日志的人就能在那个 QQ 完成群内验证之后抢先注册。
 * 理由与 {@link com.kaede.uspace.user.dto.QqVerifyStatusRequest} 的类注释同源。
 */
@RestController
@RequestMapping("/api/user/qq-verify")
public class QqVerifyController {

    private final QqVerifyService qqVerifyService;

    public QqVerifyController(QqVerifyService qqVerifyService) {
        this.qqVerifyService = qqVerifyService;
    }

    /**
     * 签发一个验证码。
     *
     * <p>同一个 QQ 重复调会<b>覆盖</b>上一次（含已验证状态），
     * 理由见 {@link QqVerifyService#issue} —— 不覆盖会让攻击者攥着旧凭证
     * 搭便车。
     *
     * @param request 含 QQ 号
     * @return 验证码（给用户，发群里）与 challengeId（给浏览器，留在页面里）
     */
    @PostMapping
    public ResponseEntity<ApiResult<QqVerifyIssueVo>> issue(
            @Valid @RequestBody QqVerifyIssueRequest request) {
        return ApiResult.of(qqVerifyService.issue(request.getQq()));
    }

    /**
     * 查询群内确认的结果（注册页轮询用）。
     *
     * <p>返回体里只有「确认了没有」与到期时刻 —— 不返回 QQ 号（前端自己填的），
     * 也不返回验证码（签发那次就给过了）。见 {@link QqVerifyStatusVo} 的类注释。
     *
     * @param request 含 QQ 号与签发时给出的 challengeId
     * @return 成功时 {@code verified} 表示群里确认了没有；
     *         挑战不存在 / 已过期 / 凭证对不上时返回 404（共用同一个错误码，不区分）
     */
    @PostMapping("/status")
    public ResponseEntity<ApiResult<QqVerifyStatusVo>> status(
            @Valid @RequestBody QqVerifyStatusRequest request) {
        return ApiResult.of(qqVerifyService.status(request.getQq(), request.getChallengeId()));
    }
}
