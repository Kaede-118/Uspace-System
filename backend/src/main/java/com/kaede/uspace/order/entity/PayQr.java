package com.kaede.uspace.order.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import com.kaede.uspace.order.PayQrChannel;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 收款码实体，对应 {@code biz_pay_qr} 表。
 *
 * <p>2026-09-30 起，收款码是 12 月投产时唯一的收款方式：店内张贴（或收银台上展示）
 * 二维码，顾客扫码转账，回到页面传付款截图，管理员核对。三条线上支付通道
 * 都因资质门槛走不通，见 {@code docs/开发约定与设计说明.md} 第九章。
 *
 * <p><b>它可以继承 {@code BaseEntity}</b>（有 created_at / updated_at / deleted），
 * 与 {@code biz_payment_proof} 刻意不继承形成对照：本表是普通的运营数据 ——
 * 一张码会被停用、改名、删掉；而凭证是财务凭据，只增不改，
 * 多出来的软删列反而会让人以为它支持撤销。
 *
 * <p><b>停用而不是删除</b>是运营上的常态：换了收款账号、被限额了、
 * 某张码暂时不用了 —— 停用一下即可，过段时间再启用。
 * 真删掉的代价不只是「再启用要重新上传」，历史凭证上的 {@code pay_qr_id}
 * 也会变成一个查不到的号（所以删除走逻辑删除，行还在，只是不再被查询命中）。
 *
 * @see PayQrChannel 渠道取值
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_pay_qr")
public class PayQr extends BaseEntity {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /**
     * 所属门店 ID。
     *
     * <p>当前是单门店，取 {@code StoreMapper.selectCurrent()} 的结果；
     * 将来开分店时每店各挂各的码，靠这一列区分，表结构不必动。
     */
    private Long storeId;

    /**
     * 收款账号渠道，取值见 {@link PayQrChannel}（{@code WXPAY} / {@code ALIPAY}）。
     *
     * <p>存字符串而不是枚举类型：与 {@code biz_order.status} 同一套做法，
     * 库里存的就是人可读的名字，排查数据时不必回查对照表。
     */
    private String channel;

    /**
     * 显示名，如「微信收款码」「老板娘的支付宝」。
     *
     * <p>收银台上可能并排展示多张码，用户靠这个名字分辨该扫哪一张；
     * 后台列表里也靠它区分。长度上限 50 与库列一致。
     */
    private String name;

    /**
     * 收款码图片的站内路径（{@code /uploads/payqr/xxx.png}）。
     *
     * <p><b>上传时不写库</b>，与商品封面同一套语义：先用
     * {@code POST /api/admin/pay-qrs/image} 拿到路径，再随新增 / 修改一起提交。
     * 代价是「传了图又取消」会留下没人引用的文件，那是这条语义的固有结果。
     *
     * <p>⚠️ <b>图片必须是 PNG 或能保留二维码可扫性的格式</b>：二维码常见透明底，
     * 转成 JPEG 后透明区会变黑，码就扫不出来了（前端 {@code processImage} 的
     * {@code payqr} 处理器专门为此输出 PNG，见 {@code utils/image.js}）。
     */
    private String imageUrl;

    /**
     * 是否启用：1 启用 / 0 停用。
     *
     * <p>停用的码不出现在收银台上，但后台列表里还在，可以随时启用回来。
     *
     * <p>用 {@code Integer} 而不是 {@code Boolean}，与 {@code biz_product.enabled}
     * 同一套：库里是 TINYINT，映射成 Boolean 会引入一层隐式转换。
     */
    private Integer enabled;

    /**
     * 排序，升序。同一门店多张码的展示顺序。
     *
     * <p>与 {@code biz_device.sort}、{@code biz_equipment_type.sort} 同名同义。
     * 查询时还会再用 {@code id} 兜一层底，否则同 sort 的两张码
     * 每次查出来的顺序可能不同，页面看起来像在抖动。
     */
    private Integer sort;
}
