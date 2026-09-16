-- ============================================================================
-- 无人值守共享娱乐空间管理系统（uspace） 数据库建表脚本
--
-- 目标库：MySQL 8.0
-- 字符集：utf8mb4 / utf8mb4_general_ci（支持 emoji 与生僻字）
--
-- 使用方式：
--   mysql -u root -p uspace < schema.sql
--   或在客户端中打开本文件执行
--
-- ⚠️ 本脚本含 DROP TABLE IF EXISTS，可反复执行以重建表结构，
--    **但会清空表中数据**。仅用于开发与演示环境；生产环境上线后
--    请改用增量迁移脚本，不要重复执行本文件。
--
-- 设计约定（详见 docs/开发约定与设计说明.md）：
--   1. 金额一律 DECIMAL(10,2)，禁止 FLOAT / DOUBLE
--   2. 时间字段存本地时间（Asia/Shanghai）。计费规则按本地时钟定义
--      （日场 10:00–22:00），存 UTC 反而多一层换算与出错机会
--   3. 主要业务表带 deleted 逻辑删除标记，订单等财务数据不允许物理删除
--   4. 表名加前缀规避保留字：ORDER、LOCK 均为 MySQL 保留字，
--      不加前缀则每处 SQL 都要写反引号，第三方工具也易踩坑
--   5. 缺少外键约束是刻意的：便于分库分表与数据清理，关联完整性由应用层保证
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


-- ============================================================================
-- 模块 1：用户管理
-- ============================================================================
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(50)  NOT NULL                COMMENT '登录名',
  `password_hash` VARCHAR(100) NOT NULL                COMMENT '密码哈希（BCrypt），绝不存明文',
  `nickname`      VARCHAR(50)  DEFAULT NULL            COMMENT '昵称',
  `phone`         VARCHAR(20)  DEFAULT NULL            COMMENT '手机号',
  `qq`            VARCHAR(20)  DEFAULT NULL            COMMENT 'QQ 号，供机器人模块匹配身份',
  `role`          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER 普通用户 / ADMIN 管理员',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=正常 0=禁用',
  `created_at`    DATETIME     NOT NULL                COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL                COMMENT '更新时间',
  `deleted`       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_qq` (`qq`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户';


-- ============================================================================
-- 模块 3：空间管理
-- ============================================================================
DROP TABLE IF EXISTS `biz_space`;
CREATE TABLE `biz_space` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(50)  NOT NULL                COMMENT '房间名，如「3号房」',
  `type`        VARCHAR(30)  DEFAULT NULL            COMMENT '房型：台球室 / 游戏厅 / 棋牌室',
  `capacity`    INT          DEFAULT NULL            COMMENT '容纳人数',
  `status`      VARCHAR(20)  NOT NULL DEFAULT 'FREE' COMMENT '状态：FREE 空闲 / IN_USE 使用中 / MAINTENANCE 维护中 / DISABLED 停用',
  `description` VARCHAR(255) DEFAULT NULL            COMMENT '房间说明',
  `created_at`  DATETIME     NOT NULL                COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL                COMMENT '更新时间',
  `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '空间（房间）';


-- ============================================================================
-- 模块 5：门禁管理
-- ============================================================================
DROP TABLE IF EXISTS `biz_lock`;
CREATE TABLE `biz_lock` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `space_id`       BIGINT       NOT NULL                COMMENT '所属房间 ID',
  `lock_name`      VARCHAR(50)  NOT NULL                COMMENT '锁名称',
  `lock_mac`       VARCHAR(20)  NOT NULL                COMMENT '通通锁 lockMac，锁的物理标识',
  `ttlock_lock_id` BIGINT       DEFAULT NULL            COMMENT '通通锁 /v3/lock/init 返回的 lockId',
  `ttlock_key_id`  BIGINT       DEFAULT NULL            COMMENT '通通锁返回的 keyId（管理员钥匙）',
  `aes_key_str`    VARCHAR(100) DEFAULT NULL            COMMENT '[敏感] 通通锁 aesKeyStr，应加密存储',
  `admin_pwd`      VARCHAR(50)  DEFAULT NULL            COMMENT '[敏感] 管理员密码，应加密存储',
  `pwd_info`       VARCHAR(255) DEFAULT NULL            COMMENT '[敏感] 密码数据，生成密码用，应加密存储',
  `gateway_id`     VARCHAR(50)  DEFAULT NULL            COMMENT '网关 ID。为空表示未接网关，无法远程下发密码（addType=2 不可用）',
  `battery`        INT          DEFAULT NULL            COMMENT '电量百分比',
  `online_status`  VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN' COMMENT '在线状态：ONLINE / OFFLINE / UNKNOWN',
  `last_sync_at`   DATETIME     DEFAULT NULL            COMMENT '最后一次与门锁云同步的时刻',
  `created_at`     DATETIME     NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL                COMMENT '更新时间',
  `deleted`        TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lock_mac` (`lock_mac`),
  KEY `idx_space_id` (`space_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '智能门锁';


-- ============================================================================
-- 模块 8：订单管理（主链路 5→6→7→8 的枢纽）
--
-- 业务模式：即时制。用户到店下单 → 远程下发密码 → 开门进入（封闭空间）
--          → 使用中 → 用户手动点「结束使用」→ 生成订单 → 离场出门
--          → 支付（H5 线上支付 / 扫收款码传截图）
--
-- 为什么是「先出门后付款」（信任制）：
--   若设计为付款后才解锁出门，人在封闭空间内遇到火灾等紧急情况会被困住，
--   存在消防隐患。因此改为信任制，用可控的欠费风险换取安全。
--
-- 计费起点是 start_time（首次开门进场）而非下单时间。
-- ============================================================================
DROP TABLE IF EXISTS `biz_order`;
CREATE TABLE `biz_order` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`        VARCHAR(32)   NOT NULL                COMMENT '业务单号，对外展示',
  `user_id`         BIGINT        NOT NULL                COMMENT '下单用户 ID',
  `space_id`        BIGINT        NOT NULL                COMMENT '使用房间 ID',
  `lock_id`         BIGINT        DEFAULT NULL            COMMENT '门锁 ID，下单时快照',

  -- 密码相关
  `passcode`        VARCHAR(10)   DEFAULT NULL            COMMENT '下发的限时密码',
  `passcode_start`  DATETIME      DEFAULT NULL            COMMENT '密码生效时间',
  `passcode_end`    DATETIME      DEFAULT NULL            COMMENT '密码失效时间',

  -- 计费区间
  `start_time`      DATETIME      DEFAULT NULL            COMMENT '首次开门进场时刻，计费起点',
  `end_time`        DATETIME      DEFAULT NULL            COMMENT '离场时刻，进行中为 NULL',

  -- 计费结果（分段存储，供账单分类展示与事后追溯）
  `day_minutes`     INT           DEFAULT NULL            COMMENT '日场时长（分钟）',
  `day_amount`      DECIMAL(10,2) DEFAULT NULL            COMMENT '日场费用',
  `night_minutes`   INT           DEFAULT NULL            COMMENT '夜场时长（分钟）',
  `night_amount`    DECIMAL(10,2) DEFAULT NULL            COMMENT '夜场费用',
  `total_amount`    DECIMAL(10,2) DEFAULT NULL            COMMENT '合计 = 日场 + 夜场',
  `discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '优惠金额（模块 9 写入）',
  `payable_amount`  DECIMAL(10,2) DEFAULT NULL            COMMENT '实付 = 合计 − 优惠',

  -- 状态
  `status`          VARCHAR(20)   NOT NULL DEFAULT 'CREATED'
                    COMMENT '状态：CREATED 已创建 / IN_USE 使用中 / PENDING_PAYMENT 待支付 / PAID 已支付 / CANCELLED 已取消',

  -- 支付（信任制：离场后才付款）
  `payment_method`  VARCHAR(20)   DEFAULT NULL            COMMENT 'H5_ONLINE 线上支付 / QR_UPLOAD 扫收款码传截图',
  `payment_proof`   VARCHAR(255)  DEFAULT NULL            COMMENT '支付截图存储路径，QR_UPLOAD 时必填',
  `payment_no`      VARCHAR(64)   DEFAULT NULL            COMMENT '线上支付流水号，H5_ONLINE 时必填',
  `paid_at`         DATETIME      DEFAULT NULL            COMMENT '支付完成时刻',
  `confirmed_by`    BIGINT        DEFAULT NULL            COMMENT '支付核销管理员 ID，为空表示系统自动确认',

  -- 人工调整（用户忘记点「结束使用」时，管理员查监控后手工修正）
  `adjusted`        TINYINT       NOT NULL DEFAULT 0      COMMENT '时长是否经人工调整：0=否 1=是',
  `adjusted_by`     BIGINT        DEFAULT NULL            COMMENT '调整人（管理员 ID）',
  `adjusted_at`     DATETIME      DEFAULT NULL            COMMENT '调整时间',
  `adjust_reason`   VARCHAR(255)  DEFAULT NULL            COMMENT '调整原因，如「用户忘记结束，监控核实 21:30 已离场」',

  `remark`          VARCHAR(255)  DEFAULT NULL            COMMENT '备注',
  `created_at`      DATETIME      NOT NULL                COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`         TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_space_status` (`space_id`, `status`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_adjusted` (`adjusted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '订单';


-- ============================================================================
-- 模块 6：进出记录
--
-- 说明：本表记录的是「开门事件」，而非严格意义上的进与出。
--       封闭空间的出门通常为机械推杠/按钮（消防要求内部免密自由开门），
--       不产生门锁记录，因此系统只能记录到「进门」这一次开门。
--       用户离场时刻由用户手动点「结束使用」确定，异常情况人工复核。
-- ============================================================================
DROP TABLE IF EXISTS `biz_access_record`;
CREATE TABLE `biz_access_record` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`   BIGINT      DEFAULT NULL            COMMENT '关联订单 ID，管理员开门等无订单场景为空',
  `user_id`    BIGINT      DEFAULT NULL            COMMENT '开门人用户 ID，可为空',
  `space_id`   BIGINT      NOT NULL                COMMENT '房间 ID',
  `lock_id`    BIGINT      NOT NULL                COMMENT '门锁 ID',
  `passcode`   VARCHAR(10) DEFAULT NULL            COMMENT '本次开门所用密码',
  `open_type`  INT         DEFAULT NULL            COMMENT '开门方式，通通锁 lockRecord 原样返回',
  `open_time`  DATETIME    NOT NULL                COMMENT '开门时刻',
  `source`     VARCHAR(20) NOT NULL                COMMENT '记录来源：MOCK / TTLOCK / QQ_BOT / ADMIN',
  `created_at` DATETIME    NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_lock_time` (`lock_id`, `open_time`),
  KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '进出（开门）记录';


SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- 后续模块建表时追加于此：
--   模块 4  设备管理     biz_device
--   模块 9  优惠管理     biz_promotion_rule
--   模块 12 系统日志     sys_log
-- ============================================================================
