package com.kaede.uspace.common.result;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。
 *
 * <p>每个枚举项携带三样东西：<b>HTTP 状态码</b>（给浏览器、代理与网关看）、
 * <b>业务码</b>（给前端判断分支用）、<b>中文消息</b>（默认展示文案）。
 *
 * <p>业务码取五位，前三位与 HTTP 状态码对齐（如 {@code 40901} 对应 409）。
 * 这样从日志里扫一眼业务码就知道大概是什么性质的问题，不必回查对照表。
 *
 * <p><b>为什么 HTTP 状态码与业务码都要有</b>：两者粒度不同，缺一不可。
 * HTTP 状态码是公开的、粗粒度的语义 —— 代理、网关、浏览器都认它，
 * 前端 axios 拦截器也靠它统一处理「登录态失效」；
 * 业务码则是细粒度的，用来区分「同样是 401，到底是没带 token、
 * token 过期，还是改密后被撤销了」。只留一个都不够用。
 *
 * <p>新增错误码时请保持两件事：业务码不与既有值重复；HTTP 状态码选语义最贴近的那个，
 * 不要一律用 500 —— 那会让「服务端出错」与「用户操作不当」在监控里无从区分。
 */
public enum ErrorCode {

    // ------------------------------------------------------------------
    // 400：请求本身有问题，改请求即可成功
    // ------------------------------------------------------------------

    /** 请求参数不合法：校验未通过、格式错误、类型不匹配 */
    PARAM_INVALID(HttpStatus.BAD_REQUEST, 40000, "请求参数不合法"),

    // ------------------------------------------------------------------
    // 401：身份未确认（没登录、凭证无效或过期）
    // ------------------------------------------------------------------

    /** 未携带认证凭证 */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, 40100, "请先登录"),

    /**
     * 凭证无效：签名错误、格式错误，或因改密 / 封禁导致版本号对不上而被撤销。
     *
     * <p>刻意把「凭证损坏」与「凭证被撤销」合并成一个码：
     * 对用户而言两种情况的处置完全相同（重新登录），分开只会把内部机制暴露给前端。
     */
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, 40101, "登录状态已失效，请重新登录"),

    /** 凭证已过期 */
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, 40102, "登录已过期，请重新登录"),

    /**
     * 用户名或密码错误。
     *
     * <p><b>「用户不存在」也复用这个码</b>，不单独给一个 USER_NOT_FOUND ——
     * 否则攻击者可以靠错误码的差异枚举出系统里有哪些用户名。
     */
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, 40103, "用户名或密码错误"),

    // ------------------------------------------------------------------
    // 403：身份已确认，但没资格做这件事
    // ------------------------------------------------------------------

    /** 已登录，但当前角色不具备该操作的权限 */
    FORBIDDEN(HttpStatus.FORBIDDEN, 40300, "没有操作权限"),

    /**
     * 账号已被禁用。
     *
     * <p>用 403 而非 401：401 的语义是「你是谁不清楚」，
     * 而这里是「知道你是谁，但你不能进」—— 两者的前端处置也不同
     * （前者跳登录页，后者应提示联系管理员）。
     */
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, 40301, "账号已被禁用，请联系管理员"),

    // ------------------------------------------------------------------
    // 404：资源不存在
    // ------------------------------------------------------------------

    /** 请求的资源不存在（含未匹配到任何接口的路径） */
    NOT_FOUND(HttpStatus.NOT_FOUND, 40400, "请求的资源不存在"),

    /** 目标用户不存在（或已被逻辑删除） */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, 40401, "用户不存在"),

    /** 门店不存在。通常是没执行建表脚本，或门店记录被误删 */
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, 40402, "门店不存在"),

    /** 停业记录不存在（或已被逻辑删除） */
    CLOSURE_NOT_FOUND(HttpStatus.NOT_FOUND, 40403, "停业记录不存在"),

    /** 包场记录不存在（或已被逻辑删除） */
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, 40404, "包场记录不存在"),

    // ------------------------------------------------------------------
    // 409：请求合法，但与当前数据状态冲突
    // ------------------------------------------------------------------

    /** 业务冲突兜底。各模块若没有更贴切的码，用它 */
    BUSINESS_REJECTED(HttpStatus.CONFLICT, 40900, "当前状态不允许该操作"),

    /** 用户名已被占用 */
    USERNAME_EXISTS(HttpStatus.CONFLICT, 40901, "该用户名已被使用"),

    /** QQ 号已被其他账号绑定。模块 11 靠 QQ 号定位用户，重号会让查询结果不确定 */
    QQ_ALREADY_BOUND(HttpStatus.CONFLICT, 40902, "该 QQ 号已被其他账号绑定"),

    /** 修改密码时原密码不正确 */
    OLD_PASSWORD_MISMATCH(HttpStatus.CONFLICT, 40903, "原密码不正确"),

    /** 新密码与原密码相同 */
    PASSWORD_UNCHANGED(HttpStatus.CONFLICT, 40904, "新密码不能与原密码相同"),

    /** 管理员不能对自己执行封禁、改角色一类的操作 */
    SELF_OPERATION_FORBIDDEN(HttpStatus.CONFLICT, 40905, "不能对自己执行该操作"),

    /** 角色取值不在允许范围内 */
    ROLE_INVALID(HttpStatus.CONFLICT, 40906, "角色取值不合法"),

    // --- 模块 3 空间管理 ---

    /** 门店名称已被占用。库里有唯一索引 {@code uk_name} 兜底 */
    STORE_NAME_EXISTS(HttpStatus.CONFLICT, 40907, "该门店名称已被使用"),

    /** 停业时段的首尾颠倒 */
    CLOSURE_TIME_INVALID(HttpStatus.CONFLICT, 40908, "停业结束时间必须晚于开始时间"),

    /** 停业时段与既有记录重叠 */
    CLOSURE_OVERLAP(HttpStatus.CONFLICT, 40909, "该时段已有停业记录，请调整或合并"),

    /** 包场时段的首尾颠倒 */
    BOOKING_TIME_INVALID(HttpStatus.CONFLICT, 40910, "包场结束时间必须晚于开始时间"),

    /** 包场开始时间早于当前时刻 */
    BOOKING_START_IN_PAST(HttpStatus.CONFLICT, 40911, "包场开始时间不能早于当前时刻"),

    /** 包场时段与既有包场重叠 */
    BOOKING_OVERLAP(HttpStatus.CONFLICT, 40912, "该时段已有其他包场"),

    /** 包场时段与停业时段重叠 */
    BOOKING_CLOSURE_OVERLAP(HttpStatus.CONFLICT, 40913, "该时段已安排停业，不能排包场"),

    /** 当前状态不允许修改或取消包场（只有待付款的可以） */
    BOOKING_NOT_EDITABLE(HttpStatus.CONFLICT, 40914, "只有待付款的包场可以修改或取消"),

    /**
     * 门店正在停业，拒绝新下单。
     *
     * <p>供模块 8 的下单校验使用 —— 模块 3 只提供「此刻是否停业」的判断
     * （见 {@code ClosureService#isClosedAt}），拒绝的动作发生在下单链路上。
     */
    STORE_CLOSED(HttpStatus.CONFLICT, 40915, "当前暂停营业，暂不支持下单"),

    // ------------------------------------------------------------------
    // 500：服务端自身出错，与请求无关
    // ------------------------------------------------------------------

    /**
     * 服务端内部错误兜底。
     *
     * <p>对外只给一句笼统提示，具体堆栈只进服务端日志 ——
     * 异常信息里常含表名、SQL、路径，返回给前端等于免费送情报。
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 50000, "服务异常，请稍后重试");

    /** 对应的 HTTP 状态码 */
    private final HttpStatus httpStatus;

    /** 业务码，五位数字，前三位与 HTTP 状态码对齐 */
    private final int code;

    /** 默认的中文提示文案 */
    private final String message;

    ErrorCode(HttpStatus httpStatus, int code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    /**
     * 取对应的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * 取业务码。
     *
     * @return 五位业务码
     */
    public int getCode() {
        return code;
    }

    /**
     * 取默认提示文案。
     *
     * @return 中文提示
     */
    public String getMessage() {
        return message;
    }
}
