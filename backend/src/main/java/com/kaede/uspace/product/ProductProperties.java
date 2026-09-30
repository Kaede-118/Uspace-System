package com.kaede.uspace.product;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 商品配置，对应配置文件中的 {@code uspace.product.*}。
 *
 * <p><b>只有一项也单开一个配置类</b>：塞进 {@code uspace.order} 会让
 * 「商品的参数」住进订单模块 —— 而本包与订单没有任何关系，
 * 两者只是共用支付入口。配置的分组应当与代码的分包一致，
 * 否则将来找「商品待支付多久超时」得先去翻订单的配置文件。
 *
 * <p><b>价格与库存不在这里</b>：它们是每一件商品各自的数据，在
 * {@code biz_product} 表里由管理员维护。配置项只放「所有商品共有的行为参数」。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.product")
public class ProductProperties {

    /**
     * 待支付购买单的存活时长，默认 30 分钟。
     *
     * <p><b>它决定的是「这笔单子还占不占库存」，不是「还能不能付」</b> ——
     * 超过它之后，这笔单子不再计入可售量的计算（别人可以买走那件商品），
     * 但用户仍然可以把它付掉。那种情况下库存多半已被买走，
     * 扣减会因为条件不满足而失败，该笔支付转入人工处理。
     *
     * <p>这个语义与月卡的 {@code pending-timeout} <b>刻意不同</b>：
     * 月卡超时后单子会被关掉、不能再付。因为月卡是「一人一卡」，
     * 未关闭的旧单会把用户自己卡死；而商品可以买多笔，
     * 关掉旧单反而是替用户做了「不买了」的决定。
     *
     * <p>默认值与月卡一致，都按最短的支付通道寿命对齐 ——
     * H5 支付链接只有 5 分钟，JSAPI 凭据 2 小时，30 分钟落在两者之间。
     */
    private Duration pendingTimeout = Duration.ofMinutes(30);
}
