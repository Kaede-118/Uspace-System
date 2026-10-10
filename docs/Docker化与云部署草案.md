# Docker 化与云部署方案（草案）

> **状态：草案** —— 写于 2026-10-03，**尚未实施、尚未实测**。定案前不要按本文改动生产环境。
>
> **2026-10-08 补**：新增第二节「部署形态与采购决策」（云服务器 vs 自建穿透、
> Linux vs Windows 裸跑），以及第八节的采购清单与域名备案约束。
> 补记内容来自当天的讨论，**未经实测**。
>
> **本文的依据**：仅来自本仓库现有文档（`CLAUDE.md`、`docs/开发约定与设计说明.md`
> 第十二章、`docs/前端与后端新增设计.md` 第五节与 2.x 各节、`docs/sql/schema.sql`、
> `启动.bat`），**未做代码级核实**；第二节里的内存数字是 2026-10-08 的
> **本机进程实测**，全文仅此一处实测数据。
>
> **两条诚实的边界**：
> ① 涉及「代码里可能存在的 Windows 依赖」那一类风险（硬编码路径、路径分隔符、
> 文件名大小写），文档层面看不出来，**以 WSL + Docker 实测为准**；
> ② 第六节给出的配置骨架是**示意**，其中标注了若干「实施时按实际核对」的点
> （jar 产物名、datasource URL、上传与对账目录的配置项默认值），
> 不要未经核对直接照抄。
>
> 与本文相关的时间线见 `CLAUDE.md`「项目定位」一节：**2026-12 投产在前、答辩在后**，
> 服务器与域名备案（2~3 周，无法加速）属长周期外部依赖，**要尽早并行启动**。

---

## 一、结论

**可行，而且属于「顺水推舟」级别，不是那种要动筋骨的容器化改造。**

粗估改造量：新增 6~7 个文件（两个 Dockerfile、两份 .dockerignore、docker-compose.yml、
nginx.conf、`.env.example`），**主链路业务代码大概率一行不用改**。

真正的工作量不在写配置，而在验证：时区、卷持久化、上传链路要各实测一遍（第七节给了清单）。

---

## 二、部署形态与采购决策（2026-10-08 补）

> 本节补的是**比容器化更前置的两个选择**：服务器放哪儿（云 vs 自建穿透）、
> 宿主用什么系统（Linux vs Windows）。两轮讨论的结论都是「按本文原本的假设走」，
> 但**被排除的路径与理由要留档** —— 与 `docs/门锁选型调研.md` 记下已弃用的涂鸦方案、
> `CLAUDE.md` 记下「为什么弃用 Native 扫码」是同一条纪律：
> **否掉的选项写清楚，将来才不必重新调研一遍。**

### 2.1 云服务器直跑 vs 自建主机 + 内网穿透

**结论：云服务器直接跑生产，不走内网穿透。**

先把一个概念说明白：**阿里云的产品目录里没有「内网穿透」**。提供这项能力的是
第三方服务（花生壳，套餐带宽很小，图片多的应用会明显慢）或自建的 **frp**
（开源，需要在云服务器上跑 server 端）—— 也就是说**走这条路照样得租一台
有公网 IP 的云服务器**，只是上面跑的是 frp 而不是 jar。

| | 云服务器直跑生产 | 中转机 + 自己主机 |
|---|---|---|
| 云服务器 | 轻量 2核2G，首年约 100-200 元 | 轻量 1核2G，首年几十到一百多元 |
| 电费 | 0 | **300-1200 元/年**（看机器功耗） |
| 合计 | **100-200 元/年** | **350-1300 元/年** |

**关键在于：两个方案都要租一台轻量服务器，差价只有几十元/年（2核2G 与 1核2G
之差），远不够付电费。方案 B 是纯亏，不是省钱。**

电费的量级（24 小时开机、按 0.55 元/度，**尚未计阶梯电价**）：
迷你主机 10-20W → 50-100 元/年；现代台式机 50-90W → 250-450 元/年；
老机器 150W+ → 700 元/年以上。⚠️ 多出的用电量还可能把家用电推进更高档位，
实际更贵。

**另外三条否决理由：**

- **可用性** —— 无人值守门店的语义是「现场没人能处理故障」。断电、断网、
  路由器死机、硬盘损坏全要自己扛，而人可能不在家。营业高峰（晚 20:00-23:00）
  恰是家宽最拥堵的时段，加上手机流量跨网访问家宽上行，门口扫码开门的
  成功率会明显更低。云服务器则有 SLA、快照、监控、工单
- **开发机 = 生产机** —— 改代码要重启服务、跑 `mvn test` 占满 CPU、晚上关机，
  每一个都是门店停摆；而开发期恰恰是改动最频繁的时期
- ⚠️ **开发库绝不直接当生产库**：`schema.sql` 含 `DROP TABLE`（脚本头部对此
  有明确警告），手滑重跑一次真实营业数据就全没了

> **家宽条款**也提一句：家庭宽带协议普遍写明「不得用于经营性对外服务」，
> 而本系统是收费系统。真被运营商盯上，限速或停机都够呛。

### 2.2 Linux + Docker vs Windows Server 裸跑

**结论：Linux + Docker。**

Windows 裸跑有一项**真实优势**，不能轻视：**零迁移风险** —— 本项目就在 Windows 上
开发（`启动.bat` / `start-backend.bat` 就是现成的 Windows 启动脚本），代码在这个
平台上跑了十几个月；而 Linux 一侧，本文**第七节**整份验证清单（时区、卷持久化、
大小写敏感）都还是待做项。换成 Windows，那些待验证项直接归零。

**但代价落在三处：**

**① 内存底噪 —— 硬账**

| | 常驻内存 |
|---|---|
| Windows Server 空载 | 1.0-1.5 GB（Linux 为 100-300 MB） |
| + MySQL 8 + JVM | +800 MB |
| **合计** | **约 2-2.5 GB** |

→ 2核2G **不够**，要 **2核4G 起**；而 Linux 方案 2核2G 能跑。

> **2026-10-08 本机实测**（供估算参照）：Spring Boot 开发模式 **319 MB**、
> MySQL 8 约 **170 MB**、前端 dist 全量 **0.44 MB / 96 个文件**。
> 生产按 MySQL 350-450 MB 估（数据量与连接数都会长）。

结果是 Windows 方案的服务器开销反而更高 —— 4G 比 2G 每年贵 200-400 元，
部分厂商的 Windows 镜像还另收授权费。

**② 无人值守化要自己搭 —— 最大的工作量**

现有的两个 `.bat` 是**开发模式**（`mvn spring-boot:run` + `npm run dev` + 交互菜单），
搬到服务器上要补：

1. 后端改 `java -jar`（打好的 jar），前端 `npm run build` 出 dist
2. 静态托管 + `/api` 同源反代（IIS+ARR 或 nginx for Windows）
3. **用 WinSW / NSSM 把 `java -jar` 包成 Windows 服务**（开机自启 + 崩溃自动重启）
   —— 不做这一步，「投产」就是赌博
4. 确认 MySQL 服务的启动类型是「自动」
5. 处理 Windows Update 的自动重启（半夜更新重启 = 门店停摆）
6. RDP 加固（改端口、强密码、能限 IP 就限）

Docker 方案里，第 3 条就是 `restart: unless-stopped` 一行。

**③ 三个 Windows 特有的静默坑**

- ⚠️ **properties 里 Windows 路径的反斜杠**：`uspace.upload.dir=C:\uspace\uploads`
  里的 `\u` 会被当成 Java 的 Unicode 转义前缀，启动时报
  `Malformed \uxxxx encoding` —— 错误信息完全指不到「路径」上。
  用正斜杠（`C:/uspace/uploads`）即可
- ⚠️ **用户级环境变量对服务不可见**：`MYSQL_PASSWORD` / `JWT_SECRET` 现在走
  `setx` 设的用户级变量，而**以 SYSTEM 账户运行的 Windows 服务读不到它们**
  —— 表现为「命令行跑得好好的，做成服务就起不来」。要配在服务包装器里
  （NSSM 的 `AppEnvironmentExtra` / WinSW 的 `<env>`）
- **nginx for Windows 官方标注 beta、单 worker** —— 本项目负载极低用得动，
  但知道有这回事

**反直觉的一点：Windows 裸跑要学的新东西其实更多。**

| | 要学会的 |
|---|---|
| Linux + Docker | `docker compose up -d` / `logs -f` / `down` —— 就这几条；系统管理、依赖、自启、崩溃恢复全被 compose 文件封装了 |
| Windows 裸跑 | WinSW/NSSM 服务化、IIS+ARR 或 nginx 反代、Windows Update 策略、服务账户与环境变量的关系 |

> 顺带否掉一条歧路：**Windows Server 上装 Docker 跑 Linux 容器**要经
> Hyper-V/WSL2 一层虚拟机，再叠一层内存开销，生产上很少这么用。
> 要么纯 Windows 裸跑，要么 Linux + Docker，**不要混**。

### 2.3 如果最终选了 Windows（备查）

若出于熟悉度仍选 Windows，路线是明确的：

1. 服务器 **2核4G**（别买 2G）
2. 生产跑 `java -jar` + `npm run build` 产物，**别把 `mvn spring-boot:run` 搬上去**
3. **NSSM / WinSW 服务化必须做** —— 这是能不能投产的分水岭
4. 路径用正斜杠、密钥配在服务包装器里（两个坑见 2.2）
5. 时间给够：无人值守化那套至少试两三天，含一次「故意重启服务器，
   看服务自己起不起来」的验证

---

## 三、现状盘点：为什么这套代码适合容器化

逐条对照现有文档，每一条都踩在 Docker 的舒适区上：

| 项目特征 | 文档依据 | 对容器化意味着什么 |
|---|---|---|
| 单体 Spring Boot，**零外部中间件** —— 无 Redis、无消息队列、无搜索引擎 | 设计说明「撤销机制」一节明确「不引入额外组件」，连 token 撤销都走 MySQL | compose 只需 3~4 个服务，没有依赖编排难题 |
| 外部集成**全是出站 HTTP 且为模拟实现** —— 门锁云、支付网关、OCR | `CLAUDE.md` 的「智能门锁 API 的核心约定」「OCR 辅助识别」 | 容器内不需要任何硬件直通、串口、摄像头 —— **没有平台相关 IO** |
| 凭据**已全部走环境变量** —— `MYSQL_USER` / `MYSQL_PASSWORD` / `JWT_SECRET` / `QQBOT_ACCESS_TOKEN` / `BAIDU_OCR_API_KEY` / `BAIDU_OCR_SECRET_KEY` | 设计说明第十二章、`启动.bat` 的检查逻辑 | 12-factor 已经做完了，Docker 的 `env_file` 直接对上 |
| 业务参数全外置（`uspace.*` 各配置项） | 设计说明「计费参数全部外置到配置文件」 | 改运营规则不必重建镜像 |
| 标准 MySQL 8.0 + 一份 `schema.sql`，**每张表显式 `utf8mb4 / utf8mb4_general_ci`** | `docs/sql/schema.sql` 各建表语句尾部 | 官方镜像的 `/docker-entrypoint-initdb.d/` 可用（见第五节第 7 条） |
| 前端是现成静态产物，且**特意选了 hash 路由** | 前端设计文档原话：「部署到任何静态服务器都能跑」 | nginx 一托即可，连 `try_files` 都不用配 |
| QQ 机器人是独立进程且**主动外连**后端（反向 WebSocket） | `CLAUDE.md`「QQ 机器人」一节的连接方式 | 容器网络天然友好：不需要暴露端口、不需要公网回调，compose 里填服务名即可 |
| `backend/.gitattributes` 已钉住 `mvnw eol=lf`；Windows 启动脚本（`*.bat`）本就被 `.gitignore` 挡在库外 | 仓库根 `.gitignore`、`backend/.gitattributes` | 换行符与「Windows 脚本残留」两个常见坑已提前避开 |

---

## 四、目标架构

### 4.1 拓扑

```
                      ┌──────────────────────────────────┐
   公网 :443  ──────► │  nginx                           │
   （HTTPS）          │   ├─ /          静态 dist/        │
                      │   ├─ /api/      → backend:8080   │
                      │   └─ /uploads/  直接读卷（只读）   │
                      └───────┬───────────────┬──────────┘
                              │               │
                      ┌───────▼──────┐  ┌─────▼───────────┐
                      │  backend     │  │  uploads 卷      │
                      │  Spring Boot │◄─┤  （读写）        │
                      └──┬───────┬───┘  └─────────────────┘
                         │       │
              ┌──────────▼──┐  ┌─▼─────────────────────┐
              │  mysql:8.0  │  │  reconcile-bills 卷    │
              │  + db 数据卷 │  │  （对账账单，读写）     │
              └─────────────┘  └───────────────────────┘

   NapCat（可选，独立容器）──► backend:8080/onebot/v11/ws
                               （反向 WebSocket：NapCat 主动外连，后端不感知其地址）
```

要点：

- **backend 不向宿主机暴露端口** —— 只在 compose 网络内可达，公网流量一律经 nginx。
  这比现在「开发时 8080 直接对局域网开放」安全（`QQBOT` 那条端点也在 8080 上）
- **NapCat 是可选件**：`uspace.qqbot.enabled` 默认 false，投产初期可以不进 compose；
  要进就用官方 Docker 镜像（**镜像名以 NapCat 官方文档为准**，不要抄本文）。
  注意服务器上用的是 **Linux / Docker 版**，不是仓库根那个 Windows 版 `NapCat.Shell/`
- **不引入任何新组件**：没有 Redis、没有对象存储、没有独立的证书管理服务 ——
  与项目一贯的「不引入额外组件」纪律一致

### 4.2 四个服务与三个卷

| 服务 | 镜像 | 暴露 | 说明 |
|---|---|---|---|
| `nginx` | nginx:1.27（或自制，含前端产物） | 80 / 443 | 静态 + 反代 + `/uploads` 托管 |
| `backend` | 自制（见第六节） | 仅 compose 内网 | Spring Boot jar |
| `mysql` | mysql:8.0 | 仅 compose 内网 | 初始化脚本 + 数据卷 |
| `napcat`（可选） | 官方镜像 | 不对外 | 反向 WebSocket 连 backend |

| 卷 | 内容 | 丢了会怎样 |
|---|---|---|
| `db-data` | MySQL 数据目录 | 全部业务数据 |
| `uploads` | 头像 / 背景图 / 商品封面 / **付款截图** / 收款码 | 图片全裂（库里存的是相对路径，路径还在、文件没了） |
| `reconcile-bills` | 对账账单原文件 | 批次详情里「下载原文件」失效，凭证与账单的勾稽依据丢失 |

> ⚠️ **`uploads` 与 `reconcile-bills` 必须是两个独立的卷挂点，不能嵌套。**
> `ReconcileBillStorage` 在启动时会校验这两个目录不重叠，重叠即拒绝启动 ——
> 这条校验是故意的（账单含全部交易对手与金额，绝不能落在匿名可访问的
> `/uploads/**` 之下），容器里挂卷时别图省事挂同一个父目录。
>
> ⚠️ **备份要覆盖三样**：库（`mysqldump`）+ `uploads` + `reconcile-bills`。
> 只备份库的话，恢复出来的系统里所有图片都是裂的、所有账单都下不动。

---

## 五、必须处理的六件事

按重要性排序。前两条是「错了会静默出错」的类型，与项目里反复强调的
「字段名写错不会有任何报错」同源。

### ① 时区 —— 第一位的坑

**为什么**：系统把**本地时间**写进了业务规则与数据库 ——
日场/夜场按 10:00–22:00 本地时钟切分、包场准入窗口、定时清场、
月卡起止日期；`schema.sql` 头部注释明确「时间字段存本地时间（Asia/Shanghai）」。
而 **容器默认 UTC**。

**症状**：白天测试一切正常，**22:00 之后的单全部算错档位**；
定时清场、月卡到期判定同理会偏 8 小时。且不报任何错。

**怎么做**（四处都要）：

| 位置 | 设置 |
|---|---|
| 应用容器 | `TZ=Asia/Shanghai`（compose environment） |
| 应用 JVM | 兜底可加 `-Duser.timezone=Asia/Shanghai` |
| MySQL 容器 | `TZ=Asia/Shanghai` **且** `--default-time-zone=+08:00`（两者不是一回事） |
| JDBC URL | 若现有 URL 里带 `serverTimezone` 参数，核对它与上面一致（实施时打开 `application.properties` 核对） |

**怎么验证**（实测命令）：

```bash
docker compose exec backend date                 # 应显示 CST +0800
docker compose exec mysql mysql -uroot -p -e \
  "SELECT NOW(), @@global.time_zone, @@session.time_zone;"
```

两个 `NOW()` 都不应是 UTC。再拿一笔跨 22:00 的订单在浏览器里走一遍预览，
金额应与本机开发环境一致。

> 容器化的好处在这里也体现出来：**时区一旦钉死，宿主服务器的时区就不再影响系统** ——
> 云服务器买来是 UTC 也无所谓。这比裸机部署省心。

### ② 三个持久化卷

`uspace.upload.dir` 与 `uspace.reconcile.bill-dir` 都要**显式配置成容器内绝对路径**，
并挂卷：

```
uspace.upload.dir        = /app/uploads          ← 卷 uploads
uspace.reconcile.bill-dir = /app/reconcile-bills ← 卷 reconcile-bills（两者不得嵌套）
```

> ⚠️ 默认值是 `${user.dir}/uploads` —— 容器里 `WORKDIR /app` 时恰好就是 `/app/uploads`，
> 看着「不用配也对」。**仍然建议显式配置**：这是「恰好对」而不是「设计成对」，
> 哪天 WORKDIR 或启动方式变了，它会**静默**指向别处，变成「图传上去了、容器一重启就没了」。
>
> 文档原话「生产环境应指向应用目录之外的绝对路径，重新部署时不会把用户图一起清掉」——
> 在容器里这句话的含义变成「卷必须挂到容器外」，语义一致。

### ③ nginx 的 `client_max_body_size`

**为什么**：nginx 默认只放行 **1MB** 请求体，而系统上传上限是 **2MB 图片**
（`uspace.upload.max-image-bytes=2097152`），对账账单文件同受全局
`multipart.max-file-size` 约束。

**症状**：413，且**后端日志一行都没有**（请求根本没到后端）——
与文档里记的「图片上传成功但页面不显示」属同一类「中间层挡下、两端都看不见」的坑。

**怎么做**：nginx 配置里 `client_max_body_size 16m;`（留足余量，账单文件可能比图大）。

> 这条在现有文档里没有，因为 nginx 部署本来还没做 —— 但它是容器化后**第一个会踩的**。

### ④ 前端 baseURL

前端默认拼的是 `http://${location.hostname}:8080`（前端文档明确警告过不能写死 localhost）。
生产上经 nginx 反代 `/api` 之后，后端不再暴露 8080，这个默认值就指错了。

**两种做法，二选一**：

- **（推荐）构建时注入**：Dockerfile 里 `ARG VITE_API_BASE_URL=/`，
  compose 的 build args 传值 —— 走**同源相对路径**，前后端同域名
- 或者改前端默认值走同源（属于改代码，按需）

> 顺带：hash 路由省掉了 `try_files $uri /index.html` 那条 ——
> 那正是当初选 hash 的理由（「少配一条 nginx 配置，否则刷新子页 404，
> 这类问题往往在演示当天才暴露」）。

### ⑤ 运行镜像与编码

- **用完整 JRE 基础镜像**（如 `eclipse-temurin:17-jre`），**不要 jlink 裁剪、
  不要为了体积换激进精简的基础镜像**。理由很具体：对账要解 **GBK 编码的支付宝账单**
  （`BillTextDecoder`），GBK 在 `jdk.charsets` 模块里，裁掉就 `UnsupportedCharsetException`
  —— 而且只在用户上传支付宝账单那一刻才暴露
- **设 `LANG=C.UTF-8`**：JDK 17 的 `file.encoding` 依赖系统 locale，
  容器里默认可能是 ASCII（JDK 18 起才默认 UTF-8，本项目 target 是 17）
- **设 `-XX:MaxRAMPercentage=75`**：让 JVM 按容器内存限制算堆，
  而不是按宿主物理内存

### ⑥ 单实例约束

`BookingClearScheduler`（包场清场）与 `MonthlyCardExpiryScheduler`（月卡到期）
都是**进程内定时任务**。

- compose 里 backend **不要扩多副本**（`deploy.replicas` 保持 1）
- 单店场景本来也不需要多副本 —— 这条只是写下来，防止将来有人顺手扩容

> 单点故障的兜底是重启策略（`restart: unless-stopped`），不是多副本。

### ⑦ 数据库初始化（顺带记在第五节）

用官方 mysql 镜像的 `/docker-entrypoint-initdb.d/`：

- 它**只在数据目录为空时执行一次** —— 天然规避「`schema.sql` 含 `DROP TABLE`、
  不能反复跑」那条约束（脚本头部注释对此有明确警告）
- ⚠️ `schema.sql` 里**没有 `CREATE DATABASE`**（现有用法是外部先建库）。
  官方镜像的 `MYSQL_DATABASE=uspace` 环境变量会自动建库，
  但默认排序规则是 `utf8mb4_0900_ai_ci`，与项目约定的 `utf8mb4_general_ci` 不一致。
  **本项目的每张表都显式指定了 `COLLATE`**，表级不受影响，风险低；
  稳妥起见可在 initdb 目录里放一个前置脚本显式建库：
  `CREATE DATABASE IF NOT EXISTS uspace DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;`
- **初始化只解决「空库」**。投产后的结构变更仍走项目现有的增量 DDL 纪律
  （每次 `ALTER` 单独执行并同步回 `schema.sql`），**不要重复执行 `schema.sql`**
- 顺带：本机踩过的「mysql 客户端默认 GBK、中文注释写乱码」那个坑，
  在官方镜像的 Linux 客户端上不存在（默认 utf8mb4），不必带
  `--default-character-set`，带上也无害

### ⑧ 中文字体（QQ 机器人名册图，2026-10-10 加）

`fw在店` 回的**名册卡片图**用 Java2D 画中文。容器里没有 CJK 字体时，
渲染器会**探测到并回落纯文本名册** —— 功能照常、只是群里看不到卡片
（`InstoreCardRenderer` 按 `canDisplay('在')` 逐个试候选字体，
一个都画不出汉字就不画，绝不发一屏方框）。装上即可：

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
```

- 候选清单在 `InstoreCardRenderer#FONT_CANDIDATES`（Noto Sans CJK SC /
  思源黑体 / 文泉驿 / 微软雅黑 / PingFang）—— **不写死某一个字体名**：
  Windows 上没有 Noto、Linux 容器里也没有微软雅黑
- 想确认装没装成功：本地跑一次渲染（或看日志里有没有
  「本机没有可用的中文字体，名册图回落文字版」那句 WARN）
- 也可以用配置 `uspace.qqbot.instore-image-enabled=false` 主动关掉名册图

---

## 六、配置骨架（示意，未经实测）

> ⚠️ 本节全部为骨架。**实施时逐个核对**：jar 产物名（`mvn package` 的实际输出）、
> `application.properties` 里的 datasource URL 与既有参数（`serverTimezone`、
> `characterEncoding` 等）、上传与对账目录配置项的当前默认值。
> 按项目纪律：**以代码与数据库为准，别把文档当事实来源**。

### 6.1 `.dockerignore`（两份，别省）

构建上下文里混进 `node_modules/`（几百 MB）或 `target/` 会让每次 build 慢一个量级：

```
# backend/.dockerignore
target/
uploads/
reconcile-bills/
.idea/
*.log

# frontend/.dockerignore
node_modules/
dist/
```

### 6.2 backend/Dockerfile（多阶段）

```dockerfile
# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# 先把依赖拉下来单独成层，源码改动时不必重拉依赖
RUN mvn -B -DskipTests dependency:go-offline
COPY src/ src/
RUN mvn -B -DskipTests package
# ⚠️ 用镜像自带的 mvn，不用 ./mvnw ——
#    Windows 上 checkout 的 mvnw 可能没有可执行位，容器里会 Permission denied

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai LANG=C.UTF-8
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar   # ⚠️ 按实际产物名核对
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

> **备选（更省服务器资源）**：云服务器配置低时，容器内跑 Maven 构建会很痛苦
> （内存 + 时间）。可改为**本机 / CI 构建好 jar 再 COPY 进镜像**，
> 或本机 `docker buildx` 构建 amd64 镜像后推送仓库。见 5.6。

### 6.3 frontend/Dockerfile

```dockerfile
FROM node:22-alpine AS build
WORKDIR /build
COPY package*.json ./
RUN npm ci --registry=https://registry.npmmirror.com
COPY . .
ARG VITE_API_BASE_URL=/
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
```

> `VITE_API_BASE_URL=/` 是**构建期**变量（Vite 的变量在 build 时被内联），
> 不是运行期环境变量 —— 换域名要重新构建前端镜像。这一点与后端配置的区别要分清。

### 6.4 nginx.conf（关键行）

```nginx
server {
    listen 80;
    client_max_body_size 16m;          # ⚠️ 见第五节第 ③ 条，默认 1m 会挡上传

    location / {
        root /usr/share/nginx/html;
        # hash 路由，不需要 try_files
    }
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
    location /uploads/ {
        alias /data/uploads/;          # 挂 uploads 卷（只读），与 backend 共享
    }
    # HTTPS 段按证书方式另配（Let's Encrypt 或云厂商证书）
}
```

### 6.5 docker-compose.yml（骨架）

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_PASSWORD}
      MYSQL_DATABASE: uspace
      TZ: Asia/Shanghai
    command: --default-time-zone=+08:00
    volumes:
      - db-data:/var/lib/mysql
      - ./docs/sql/schema.sql:/docker-entrypoint-initdb.d/10-schema.sql:ro
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-p${MYSQL_PASSWORD}"]
      interval: 5s
      retries: 20
    restart: unless-stopped

  backend:
    build: ./backend
    environment:
      TZ: Asia/Shanghai
      MYSQL_USER: root
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      # 覆盖 datasource 地址：环境变量优先于 properties 文件，
      # host 从 localhost 换成服务名即可，其余参数以现有 URL 为模板照抄
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/uspace?（参数照抄现有 URL）
      # QQBOT_ACCESS_TOKEN / BAIDU_OCR_API_KEY / BAIDU_OCR_SECRET_KEY 按需
    volumes:
      - uploads:/app/uploads
      - reconcile-bills:/app/reconcile-bills
    depends_on:
      mysql:
        condition: service_healthy
    restart: unless-stopped

  nginx:
    build: ./frontend
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - uploads:/data/uploads:ro
    depends_on:
      - backend
    restart: unless-stopped

volumes:
  db-data:
  uploads:
  reconcile-bills:
```

> ⚠️ 「环境变量优先于 properties」是 Spring Boot 的既定行为，所以
> **不必改一行代码**就能把数据库地址从 localhost 换成服务名 ——
> 但前提是 `application.properties` 里的 URL 参数（时区、编码）要先核对清楚。

### 6.6 `.env` 与 `.env.example`

- `.env`（真实密钥）**不进仓库** —— 根 `.gitignore` 已挡住 `.env`，正好
- `.env.example` 可以进（不被现有规则命中），内容：

```bash
MYSQL_USER=root
MYSQL_PASSWORD=
JWT_SECRET=            # ≥32 字节，注意按【字节】而非字符计
# QQBOT_ACCESS_TOKEN=
# BAIDU_OCR_API_KEY=
# BAIDU_OCR_SECRET_KEY=
```

---

## 七、WSL 本地验证方案

### 7.1 一个反直觉的先说清楚

**在 Windows 上装 Docker Desktop，容器本来就跑在 Linux 内核上（WSL2 backend）。**
所以「Linux 环境适用性」在你第一次 `docker compose up` 的那一刻就已自动验证 ——
不需要额外搭一套模拟环境。

WSL 的**额外**价值在两处：

1. **大小写敏感的验证**：前端 import 路径大小写不一致在 Windows 上能过、
   在 Linux 构建时当场失败 —— 这是 WSL 测试最值钱的一项，也是 Windows 上用
   Docker Desktop 直接 build 时同样能暴露的（因为构建也发生在 Linux 容器里）
2. **开发流程整体搬进 Linux**：bash、compose、文件权限、换行符，
   与云服务器操作同构，减少「上服务器才发现不会用」的落差

### 7.2 两个实操注意

- **代码 clone 到 WSL 内部文件系统**（如 `~/projects/uspace`），
  不要在 `/mnt/c/Desktop/...` 上直接构建 —— 9p 挂载会让 `node_modules`
  与 Maven 构建慢好几倍，inotify（热重载依赖它）也时灵时不灵
- WSL 里跑 `tools/` 那三个验收脚本需要 Linux 版浏览器（设 `CHROME_PATH`），
  或直接跳过 —— 它们不是交付物，只是验收工具

### 7.3 验证清单（逐条跑）

1. `docker compose up -d` → `docker compose ps` 全部 healthy
2. 首次初始化后确认 19 张表建齐、初始管理员可登录
3. **时区**：第五节 ① 的两条命令
4. **主链路**：登录 → 开门计时 → 看到密码 → 结账 → 传付款截图 → 后台复核
5. **卷持久化**：传一张头像 → `docker compose down` → `up` → 头像还在
6. **上传链路**：2MB 附近的图能传上去（验证 `client_max_body_size` 真的生效）；
   换成 1.5MB 与 2.5MB 各试一次，边界行为与后端报错文案一致
7. **对账**：上传一份合成账单（用 `tools/api-contract-check.mjs` 造的
   `CHECK-` 前缀那份即可），确认解析与差异页正常
8. **大小写**：如果前端 build 能过，这一项基本就过了
9. 浏览器 DevTools 手机尺寸走一遍，确认 PWA 相关路径不受影响

### 7.4 WSL 里验证不了的

- 云服务器真实性能（内存吃紧与否）—— 建议规格 2C2G 起，1C1G 对
  MySQL 8 + Spring Boot 的组合会很紧张
- 公网 HTTPS / 域名 / 备案（可先自签证书演）
- NapCat 的 QQ 登录（要真账号，且容器里走 WebUI 扫码）

---

## 八、上云与投产顺带事项

不属 Docker 化本身，但同一批做掉最省事：

| 项 | 说明 |
|---|---|
| **HTTPS** | PWA / Service Worker 需要 secure context，将来接支付也需要 —— nginx + 证书（Let's Encrypt 或云厂商免费证书） |
| **`uspace.web.base-url` 改成真实域名** | 它是拼邀请链接用的，不改的话包场邀请链接指向 localhost |
| **CORS 从 `*` 收紧** | 后端现在放开所有来源（开发期有意为之，且不配 proxy 正是为了暴露 CORS 问题）。同源部署后 CORS 根本不需要，收紧到自己的域名即可 |
| **`/uploads/**` 匿名可访问 + 文件名可枚举** | 头像是 `{用户名}_{ID}.jpg`，能被遍历猜测；付款截图在 `/proof/` 子目录下同样匿名。上公网前评估一次（本地开发无所谓） |
| **初始管理员口令替换** | `schema.sql` 预置的 `admin/admin123` 在公开仓库里人尽皆知，**投产前必须在目标库上直接设强口令**（做法见 `CLAUDE.md`：部署时删掉脚本里那条 INSERT，脚本本身不改） |
| **数据迁移** | 本机开发库 → 服务器 = 三样一起搬：`mysqldump` 的库 + `uploads` 目录 + `reconcile-bills` 目录 |
| **备份** | 定时任务覆盖三样（见第四节的警告框） |
| **镜像分发（国内服务器）** | Docker Hub 拉取在国内云上受限，走云厂商镜像加速器或 ACR/TCR 个人版；基础镜像（temurin / mysql / nginx）也可先在本机拉好 `docker save`，传上去 `docker load` |
| **答辩演示** | 容器化后一条 `docker compose up` 起全栈，比开两个终端跑 `mvn` / `npm` 稳 —— 与既有「先 build、用产物演示」的约定方向一致，是升级不是替代 |

### 8.1 采购清单（阿里云，2026-10-08 补）

| 买什么 | 产品名 | 规格 | 备注 |
|---|---|---|---|
| **生产服务器** | **轻量应用服务器**（Simple Application Server） | **2核2G**，3-5Mbps，40-60G 盘 | Linux（Ubuntu 22.04 / Alibaba Cloud Linux）。要跑 NapCat 或想留余量则 **2核4G** |
| 备选 | 云服务器 ECS | 经济型 e 实例 2核2G | 更灵活（带宽、盘单独买、可升降配），但配置项多、价格高 |
| 域名 | 阿里云域名（万网） | `.cn` 或 `.com` | 见 8.2 |
| HTTPS 证书 | 数字证书管理服务 | **免费 DV 证书** | 一年期，到期重新申请 |
| ~~中转机~~ | ~~轻量 1核2G~~ | —— | **仅在走内网穿透时才需要**，理由见 2.1 |

**地域选择：**

- **中国内地**（杭州/上海/北京）：必须 ICP 备案（2-3 周），且**备案要求轻量服务器
  购买时长 ≥ 3 个月**
- **中国香港**：**不需要备案，买了就能用**；代价是延迟略高（50-100ms vs 20-30ms）
  —— 本系统全是本地手机用户访问 API，这点延迟无感。将来要接微信支付时域名必须
  备案，届时再迁回境内（`mysqldump` + `uploads` + `reconcile-bills` 一起搬，
  半小时的事）

**别买什么**：1核1G（MySQL 8 + JVM 起不来）、Windows 镜像配 2G 内存（见 2.2）。

### 8.2 域名与备案：主体资格是硬约束（2026-10-08 补）

**ICP 备案分两种主体，能做的事完全不同：**

| 主体 | 能备案什么 | 管制点 |
|---|---|---|
| **个人** | 个人博客、作品展示这类**非经营性**网站 | 站名与内容不能涉及「XX 管理」「XX 系统」「交易」「支付」等词，**不能有经营行为** |
| **单位**（企业 / 个体工商户） | 经营性网站 | 无此限制 |

⚠️ **本系统是明摆着的经营性网站**（用户下单、付款、传付款凭证）。
**用个人身份备案 = 备案信息与实际内容不符**，抽查发现会被注销；
且各省管局尺度不一，有的省连「共享娱乐空间管理系统」这种站名都不给个人过。

⚠️ **由此与「办执照」这条长周期路径直接咬合**：没有执照就只能个人备案，
而个人备案撑不起这个系统。另注意 **域名注册人必须与备案主体一致** ——
先以个人名义注册、之后过户到个体户要走过户流程，多一道麻烦。
**如果执照在办，等执照下来一步到位注册 + 备案。**

**`.cn` 与 `.com` 的差异**（两者都需实名、都需与备案主体一致，此处只列区别）：

| | `.cn` | `.com` |
|---|---|---|
| 管理机构 | CNNIC（国内） | ICANN（国际） |
| 实名认证 | **强制**，未实名直接 `serverHold`（停止解析） | 同样要实名，由注册商执行 |
| 价格 | 通常更便宜（首年几元起，**续费也常低于 `.com`**） | 首年几十元，续费约 70-90 元/年 |
| 政策风险 | 直接受国内政策影响 | 相对隔离 |

⚠️ **`.xyz` / `.top` / `.club` 这类便宜后缀，必须先在工信部批复的域名后缀名单里
才能备案** —— 有人买了 1 元域名才发现备不了案。`.cn` / `.com` / `.net` 都在名单里。

**卡在执照/备案时的过渡路线：**

```
现在     → 阿里云香港轻量（免备案，立刻能用），先跑起来
执照下来 → 境内服务器 + 个体户备案（2-3 周）
        ↓ mysqldump + uploads + reconcile-bills 三样一起搬
切换完成（香港那台可留作测试环境）
```

> 政策与审核尺度各省不一且会变，域名与备案的最终要求以
> **阿里云备案控制台的实时提示**为准，别拿第三方教程当依据。

---

## 九、待验证与待定

**待实测（本文标注「未经实测」的全部内容）**：

- 第六节骨架的每一处「按实际核对」点（jar 产物名、datasource URL 参数、配置项默认值）
- 时区设置对**定时任务**的实际影响（清场、月卡到期），需要把容器时区**故意设错**
  再看一次表现，确认验证方法本身有效
- 上传边界：`client_max_body_size` 与后端 `multipart.max-file-size` 的关系
- NapCat 容器版的接入（镜像名、配置位置、与 `QQBOT_ACCESS_TOKEN` 的对齐方式）

**待定（未拍板，涉及时要先与用户确认）**：

- 镜像构建位置：本机构建推仓库 vs 服务器上构建（取决于服务器规格与 Docker Hub 可达性）
- 是否引入 Flyway / Liquibase 统一管理增量 DDL —— 现有纪律是「手工增量 + 同步回
  `schema.sql`」，容器化**不强制**改变它，但如果将来要「一条命令重建环境 +
  一条命令升级环境」，迁移工具是正路
- NapCat 是否进 compose（取决于 QQ 机器人投产时是否上线）
- 数据库容器是否换用云厂商托管 MySQL（RDS）—— 本方案按自建容器写，
  换 RDS 的话 `SPRING_DATASOURCE_URL` 指过去即可，其余不变

---

## 十、定案后需要同步的文档

按项目「两份文档写着相互矛盾的约定，比哪一条对更危险」的纪律，本草案定案后：

| 文件 | 改什么 |
|---|---|
| `docs/开发约定与设计说明.md` | 第十二章「构建与运行」补容器化小节（或并入本文档内容） |
| `CLAUDE.md` | ✅ 「现有文档」清单**已于 2026-10-03 加入本文**（草案阶段即加、标注未实施）；「常用命令」待定案后视情况补 compose 命令 |
| `AGENTS.md` | 同 CLAUDE.md 的同步 |
| `.gitignore` | 新增项检查：`Dockerfile` / `docker-compose.yml` / `.env.example` 均未被现有规则挡住，**不需要改**；`.env` 已被挡住，正好 |
| 毕设交付物 | 「部署文档」一项由本文档演进而来（`模块初稿设计.md` 交付物清单里有这一项） |
