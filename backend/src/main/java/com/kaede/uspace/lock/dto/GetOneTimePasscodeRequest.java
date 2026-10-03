package com.kaede.uspace.lock.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 获取一次性密码（单次密码）的请求参数。
 *
 * <p>字段命名与语义对齐通通锁云 API 的 {@code POST /v3/keyboardPwd/get}，
 * 目的是将来从模拟实现切换到真实调用时，上层代码无需改动。
 *
 * <p>与通通锁参数的对应关系：
 * <pre>
 *   lockId              -> lockId
 *   keyboardPwdName     -> keyboardPwdName
 *   startTime           -> startDate（Unix 毫秒级时间戳）
 *   keyboardPwdVersion  -> keyboardPwdVersion
 * </pre>
 *
 * <p>⚠️ <b>它与 {@link AddPasscodeRequest} 是两条不同的接口，不要合并</b>：
 * 那个对应 {@code keyboardPwd/add}，添加的是<b>可自定义内容的限时密码</b>；
 * 本类对应 {@code keyboardPwd/get}，拿到的是一串<b>由锁云生成的一次性密码</b>
 * （{@code keyboardPwdType=1}），<b>密码内容不可指定</b> ——
 * 这正是「单次密码」在通通锁上的唯一取得方式，也是它必须另开一个方法的原因。
 *
 * <p>一次性密码的固有规则（<b>不由本系统决定</b>）：
 * 自生效时刻起 <b>6 小时内只能使用一次</b>。
 */
@Data
public class GetOneTimePasscodeRequest {

    /** 锁 ID。由通通锁 {@code /v3/lock/init} 返回，或从锁列表接口获取 */
    @NotNull(message = "锁 ID 不能为空")
    private Long lockId;

    /** 密码名称，便于在通通锁后台识别用途，如「群指令-订单20261004001」 */
    private String keyboardPwdName;

    /**
     * 生效时刻。留空则由实现取「当前时刻」。
     *
     * <p>一次性密码的 6 小时窗口从它开始算。本系统总是传当前时刻
     * （群里发密码的那一刻就是用户要进门的那一刻）。
     */
    private LocalDateTime startTime;

    /**
     * 键盘密码版本，对应通通锁的 {@code keyboardPwdVersion}。
     *
     * <p>三代锁为 {@code 4}，也是当前支持「获取密码」这一接口的版本。
     * 照 {@link AddPasscodeRequest#getAddType()} 的先例，把真实 API 的参数
     * 放进请求并给默认值，使将来的真实实现不必自己硬编码。
     */
    private Integer keyboardPwdVersion = 4;
}
