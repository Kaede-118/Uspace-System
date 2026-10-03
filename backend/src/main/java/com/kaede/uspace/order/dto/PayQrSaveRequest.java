package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增或修改一张收款码的请求体。
 *
 * <p>新增与修改共用一个请求体：两者的字段完全一致，而分成两个类
 * 会让「改了其中一个忘了另一个」成为可能。
 *
 * <p><b>这是全量替换（PUT）语义</b>：没传的字段就是清空。唯一的例外是
 * {@code sort} —— 它可空，不传按 0 处理（新建时两张码都留默认值 0 很常见）。
 * 其余字段都标了校验注解，传 null 会得到 400 而不是一个语焉不详的 500。
 *
 * <p>{@code imageUrl} 只做长度与非空校验，<b>路径前缀由 Service 校验</b> ——
 * 那需要 {@code uspace.upload.url-prefix} 配置，是 Service 才拿得到的东西。
 * 少了那道校验，管理员（或伪造请求的人）能填一个外链，
 * 顾客的浏览器就会去加载别人的服务器。
 */
@Data
public class PayQrSaveRequest {

    /** 渠道取值，见 {@code PayQrChannel}。合法性由 Service 校验并给出中文提示 */
    @NotBlank(message = "请选择收款渠道")
    private String channel;

    /** 显示名，如「微信收款码」 */
    @NotBlank(message = "请填写显示名")
    @Size(max = 50, message = "显示名不能超过 50 个字")
    private String name;

    /** 图片站内路径。先调上传接口拿到，再随本请求提交 */
    @NotBlank(message = "请先上传收款码图片")
    @Size(max = 255, message = "图片路径过长")
    private String imageUrl;

    /**
     * 是否启用：1 启用 / 0 停用。
     *
     * <p><b>标 {@code @NotNull} 而不是让它可空</b>：这是整个请求里
     * 最不能出错的一个字段 —— 漏传时若默认成「停用」，收银台上那张码
     * 会静默消失，顾客付不了款而管理员不知道是自己少点了一下；
     * 若默认成「启用」，一张管理员本想停掉的码会继续收款。
     * 两种默认都不对，所以强制传。
     */
    @NotNull(message = "请指明是否启用")
    private Integer enabled;

    /** 排序值，可空。不传按 0 处理 */
    private Integer sort;
}
