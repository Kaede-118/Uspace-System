package com.kaede.uspace.auth;

import com.kaede.uspace.auth.dto.LoginRequest;
import com.kaede.uspace.auth.dto.LoginVo;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.user.UserService;
import com.kaede.uspace.user.dto.UserProfileVo;
import com.kaede.uspace.user.entity.SysUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 认证服务（模块 2）。
 *
 * <p>把「校验凭据」与「签发凭证」两步串起来。这两步分属两个模块 ——
 * 前者是模块 1 的 {@link UserService#verifyCredentials}（它不知道 JWT），
 * 后者是本包的 {@link JwtService}。本类只做编排，不含任何业务规则。
 *
 * <p><b>为什么值得单独一个类</b>：Controller 应当只做协议转换，
 * 而登录本身是有编排逻辑的（校验 → 失败则透传错误码 → 成功则签发 → 组装返回体）。
 * 把这些放进 Controller，将来若 QQ 机器人或其他入口也需要「用户名密码换凭证」，
 * 就得复制一遍。
 */
@Slf4j
@Service
public class AuthService {

    /** 凭证类型，按 OAuth2 约定固定为 Bearer */
    private static final String TOKEN_TYPE = "Bearer";

    private final UserService userService;
    private final JwtService jwtService;

    public AuthService(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    /**
     * 登录：校验凭据并签发凭证。
     *
     * <p>校验失败时<b>原样透传错误码</b>，不在这里改写 ——
     * 「用户名或密码错误」与「账号已禁用」的区别、以及
     * 「用户不存在」要伪装成「密码错误」这些规则，
     * 都由模块 1 集中决定，避免在多个入口各判一遍而慢慢走样。
     *
     * @param request 登录请求
     * @return 成功时返回凭证与用户资料；失败时透传模块 1 给出的错误码
     */
    public BizResult<LoginVo> login(LoginRequest request) {
        BizResult<SysUser> verified =
                userService.verifyCredentials(request.getUsername(), request.getPassword());
        if (!verified.isSuccess()) {
            log.warn("[认证] 登录失败 username={} 原因={}",
                    request.getUsername(), verified.getError());
            return BizResult.fail(verified.getError(), verified.resolveMessage());
        }

        SysUser user = verified.getData();
        String token = jwtService.issue(user.getId(), user.getRole(), user.getTokenVersion());

        LoginVo vo = new LoginVo();
        vo.setToken(token);
        vo.setTokenType(TOKEN_TYPE);
        vo.setExpiresIn(jwtService.getExpireSeconds());
        vo.setUser(UserProfileVo.from(user));

        log.info("[认证] 登录成功 userId={} username={} role={}",
                user.getId(), user.getUsername(), user.getRole());
        return BizResult.ok(vo);
    }

    /**
     * 登记登出。
     *
     * <p><b>不做任何实质操作</b>，只记一条日志。理由：
     * JWT 是无状态的，服务端没有「会话」可销毁；前端把 localStorage 里的
     * 凭证删掉，登出就完成了。
     *
     * <p>那为什么不干脆不提供这个接口？因为「登出」是前端必须有的动作，
     * 留一个端点便于统一记日志（谁在什么时候登出，属于模块 12 的审计素材），
     * 也让前端有个明确的调用点，而不是在代码里悄悄删掉一个 key。
     *
     * <p><b>刻意不用「把 token 版本号 +1」来实现登出</b>：那确实能让旧凭证
     * 立即失效，但它作用于<b>该用户的所有设备</b> —— 在手机上点了登出，
     * 平板上的登录态也一并被踢掉。那不是登出该有的语义，
     * 而是「强制下线」，应当留给封禁与改密使用。
     *
     * @param userId 登出的用户 ID
     */
    public void logout(Long userId) {
        log.info("[认证] 用户登出 userId={}（凭证由前端自行删除，服务端无需处理）", userId);
    }
}
