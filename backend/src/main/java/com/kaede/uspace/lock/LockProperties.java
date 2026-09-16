package com.kaede.uspace.lock;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 门锁模块配置，对应配置文件中的 {@code uspace.lock.*}。
 *
 * <p>用途：在不改动任何业务代码的前提下，切换门锁服务的实现方式
 * —— 演示时用 {@code mock}，接入真实门锁后改为 {@code ttlock}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.lock")
public class LockProperties {

    /**
     * 实现方式。
     * <ul>
     *   <li>{@code mock} —— 模拟实现，不访问外网（默认值）</li>
     *   <li>{@code ttlock} —— 真实通通锁云 API（尚未实现）</li>
     * </ul>
     */
    private String provider = "mock";

    // ------------------------------------------------------------------
    // 以下为通通锁开放平台凭据，provider=mock 时无需填写
    // ------------------------------------------------------------------

    /** 开放平台应用的 client_id（即 app_id） */
    private String clientId;

    /** 开放平台应用的 client_secret */
    private String clientSecret;

    /**
     * 通通锁 APP 的登录账号。
     *
     * <p><b>注意</b>：不能用开放平台的开发者账号 —— 开发者账号名下没有任何设备，
     * 用它换取 token 后查不到锁、也无法下发密码，会报非管理员权限。
     * 必须使用已在 APP 中绑定并授权了门锁的管理员账号。
     */
    private String username;

    /** 通通锁 APP 登录密码的 32 位小写 MD5 值（接口要求传 MD5，不传明文） */
    private String passwordMd5;

    /** 模拟实现的专用配置，provider=mock 时生效 */
    private Mock mock = new Mock();

    /**
     * 模拟实现的配置项。
     *
     * <p>这些开关的目的是让演示能够覆盖真实场景中的各种分支 ——
     * 尤其是失败分支，否则异常处理逻辑在演示时永远走不到。
     */
    @Data
    public static class Mock {

        /**
         * 模拟网络调用失败的概率，取值 0~1。
         *
         * <p>默认 0（从不失败）。演示异常处理时调大，例如 0.3 表示三成概率失败。
         */
        private double failureRate = 0.0;

        /** 模拟单次调用的网络延迟（毫秒）。默认 0，调大可观察异步处理与超时逻辑 */
        private long latencyMillis = 0;

        /**
         * 模拟的锁是否支持远程下发。
         *
         * <p>置为 {@code false} 可模拟「纯蓝牙锁」—— 此时 {@code addType=2} 的远程下发
         * 会被拒绝（对应真实场景中未接网关的锁），只能走 {@code addType=1}。
         */
        private boolean remoteSupported = true;
    }
}
