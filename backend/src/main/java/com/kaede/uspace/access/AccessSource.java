package com.kaede.uspace.access;

/**
 * 开门记录的来源。
 *
 * <p>取值与建表脚本里 {@code biz_access_record.source} 列的注释一一对应。
 * 做成枚举而不是散落的字符串常量，是为了让「合法取值有哪些」在代码里有一处权威定义。
 *
 * <p><b>为什么没有 {@code QQ_BOT}</b>：QQ 机器人自 2026-09-28 起改为只读的
 * 信息播报与查询端，不再承载开门、结账等业务操作，因此不会产生该来源的记录。
 * 建表脚本早先列有该取值，已一并移除。
 *
 * @see com.kaede.uspace.lock.LockService 门锁服务，开门记录的上游
 */
public enum AccessSource {

    /** 模拟门锁。对应 {@code uspace.lock.provider=mock}，用于开发与答辩演示 */
    MOCK("模拟门锁"),

    /** 真实通通锁云。记录由锁产生、经网关上报云端，本系统主动拉取 */
    TTLOCK("门锁云"),

    /** 管理员手工补录。系统不可用期间的纸质登记，事后由管理员补入 */
    ADMIN("管理员补录");

    /** 中文名称，用于日志与前端展示 */
    private final String label;

    /**
     * 构造方法。
     *
     * @param label 中文名称
     */
    AccessSource(String label) {
        this.label = label;
    }

    /**
     * 获取中文名称。
     *
     * @return 如「模拟门锁」「门锁云」「管理员补录」
     */
    public String getLabel() {
        return label;
    }

    /**
     * 按门锁实现方推断记录来源。
     *
     * <p>同步开门记录时用它决定落库的 {@code source} ——
     * <b>同一条记录从哪个实现拉来的，就记哪个来源</b>，不需要调用方传。
     *
     * <p>配置项 {@code uspace.lock.provider} 目前只有 {@code mock} 与 {@code ttlock}
     * 两个取值，非 {@code mock} 一律视为真实门锁云。<b>将来新增 provider 时必须回来改本方法</b> ——
     * 漏改不会报错，只会让新来源的记录被错标成 {@code TTLOCK}。
     *
     * @param provider 门锁实现方配置值，可为 null（视为真实门锁云）
     * @return {@code mock}（不分大小写）返回 {@link #MOCK}，其余返回 {@link #TTLOCK}
     */
    public static AccessSource fromProvider(String provider) {
        return "mock".equalsIgnoreCase(provider) ? MOCK : TTLOCK;
    }
}
