# 图书馆座位预约系统 · 项目报告

> 版本：V2（2026-10-13 收口）｜仓库根：`D:\Project\LibraryReservation`｜本文由代码与验收证据逐项核对后写成，数字均可复算。

---

## 0. 摘要

一个**教学演示向但按生产题库标准落地**的图书馆座位预约系统：后端 Spring Boot 3.2.5（Java 21）+ 前端 React 18 / Vite 5 / Ant Design 5，单仓库、单端口（8081）部署，前端产物打进同一个 jar。

V1 交付了"楼层 → 座位 → 预约 → 签到"的主干；V2 在其上补齐了六块相对硬的能力：**分区规则载体、暂离（保留座位）、按桌组队、高峰调控与候补、信用分与申诉、巡查看板与通知中心**，并把数据库建表权整体交给 **Flyway**（`V1..V24`，H2 与 MySQL 两套方言树）。

质量基线（均为最终交付包上实测）：**单元/接口测试 298 条全绿（Skipped 0）**；**V2 API 冒烟 104 条全绿**（演示库与全新空库各一遍，零 SKIP）；SPA 深链 **35 条全部可刷新**；浏览器控制台 **JS 与网络两条通道 0 error**；数据库迁移在 **H2 与真 MySQL 8.0.40 两条路径上分别执行并验证**。

---

## 1. 项目说明

### 1.1 背景与目标

图书馆自习座位"占而不用"是典型痛点：有人放书占座、有人刷完卡就离开、考试周一座难求。系统把"座位"变成**可预约、可签到、可暂离、可追责**的资源，并给馆员提供现场处置与巡查工具。

V1（已完成独立回归）：登录注册、楼层与座位平面图、30 分钟粒度预约、签到/结束、管理端基础 CRUD。
V2（本报告范围）：把"规则"从代码里搬进数据库，并补齐信用与协同场景。

### 1.2 功能全景（按角色）

| 角色 | 主要能力 |
| --- | --- |
| 学生（STUDENT） | 看平面图下单（可提前 3 天）、我的预约/候补/组队、签到→暂离→返回→结束、信用与违规、申诉、站内通知、移动端壳（`/m/**`，底部 5 个 Tab） |
| 教师（TEACHER） | 学生能力 + 单次上限放宽到 6 小时（档位与服务端校验同源） |
| 馆员（LIBRARIAN） | 巡查看板（登记疑点→提醒/强制释放/裁定违规/忽略）、申诉复核、监控大屏、台账导出、分区与桌位维护 |
| 系统管理员（SYS_ADMIN） | 以上全部 + 规则配置中心（改后立即生效）、开放档案与开放日历、批量建座、用户管理、审计与规则历史 |

V2 的六块能力：

1. **分区（Zone）成为规则载体**：静音/研讨/电子阅览/休闲/考研专座等分区各自可覆盖 最大时长、单日累计、可提前天数、是否允许暂离、暂离上限、组队开关与可见/可用角色；**分区值非空即取代全局默认**（不是取 min）。
2. **暂离**：原因字典（就餐/上课 60 分钟、接听/短休 20 分钟、取物 15 分钟、其他）带每日次数上限；暂离期间座位保留、超时自动释放并记违规；考研专座可由分区把"标准上限级"原因放宽到 120 分钟（`t_leave_reason.relaxable` 标记，`V21`）。
3. **按桌组队**：2~6 人、按桌整单下单、开始前 30 分钟锁定名单；组长签到=全队签到；成员缺席记违规但座位保留；组长缺席全队作废。
4. **高峰调控与候补**：按日型+分区配置高峰窗口，高峰内单次时长压缩、超载时返回 `4291` 提示排队；满座时进候补队列，有释放即发 offer（带截止时间），确认即下单。
5. **信用分与申诉**：初始 100；爽约/暂离超时/缺席 −10、组长缺席 −20、现场占座 −15；每周无违规 +5、连续 5 次主动结束 +5；阶梯处罚 `<90 高峰受限 → <70 停 3 天 → <50 停 7 天 → 0 分停至学期末`；任何扣分都带可追溯证据，学生 24 小时内可申诉，馆员复核可撤销并回补分数（回补**不会**延长在生效的禁约期）。
6. **巡查与通知**：疑点只能由"有人/无预约占用"等事实触发机器动作，疑似占座必须人工裁定；所有处置给学生发站内通知（`t_notice` + 前端轮询；本系统**没有推送通道**，页面关着收不到提醒——UI 上已明说）。

### 1.3 技术栈与规模

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 3.2.5、Java 21、Spring Data JPA、Spring Security + JWT、Flyway 9.22.3（`flyway-core` + `flyway-mysql`）、H2（默认）/ MySQL 8（可选 profile）、HikariCP |
| 前端 | React 18.3、Vite 5.4、Ant Design 5.20、React Router 6.26、Axios、Day.js、ECharts（大屏） |
| 部署 | 单端口 jar（前端产物复制进 `classpath:static`），SPA 用**显式白名单**转发实现深链刷新 |

规模（机器统计）：后端 Java 文件 **173** 个（controller **27**）、测试类 **34** 个、前端页面组件 **30** 个、Flyway 迁移 **24** 版 × 2 方言、数据库表 **24** 张。

### 1.4 架构要点

- **前后端同源**：`Vite build → dist/` 复制到 `backend/src/main/resources/static/`，打包进 jar；`WebConfig` 用白名单（35 条路径）把 history 路由转发到 `index.html`，未登记路径保持 404（便于暴露坏链）。
- **权限矩阵单一来源**：`/api/**`（登录即可）、`/api/staff/**`（馆员/管理员）、`/api/admin/**`（仅管理员）按前缀映射；后端强校验，前端菜单同源生成。
- **错误码体系**：`4001` 参数/语义、`4010` 未登录、`4030/4031` 越权、`4040` 不存在、`4090~4099` 冲突与闸门（重复注册/时段冲突/座位冲突/未签到/不可暂离/单日上限…）、`4100/4101` 处罚禁约、`4102` 仅组队分区、`4109` 桌位被占用、`4291` 高峰排队、`5000` 兜底；**405/解析失败等也都给可读文案**（不甩 5000）。
- **占用派生**：座位"被占"永远由 `t_reservation_slot` 实时派生（座位表不冗余占用字段）；唯一索引兜底"同一座位同一时段一人"，不依赖先查后插。
- **定时任务**：签到超时判爽约、暂离超时释放、候补 offer 过期、高峰状态刷新、通知过期清理等（入口均支持注入时间，便于用例验证）。
- **规则配置中心**：规则值存 `t_rule_config`（全局）与分区字段（本区口径），改后**立即生效、无需重启**，变更前后值可查历史。

### 1.5 关键设计决策（踩过坑之后定下来的）

1. **时间模型**：30 分钟一格，**格号 = 绝对小时 × 2**（07:30=15，22:30=45 为结束边界）——所以放宽"可约网格"不需要搬运任何历史占用行。
2. **种子有两个写者**：`t_user/t_floor/t_seat/t_zone` 由 **Java `DataInitializer`** 种；`t_rule_config/t_open_profile/t_leave_reason/t_peak_window` 由 **SQL 迁移**种。**迁移永远跑在 Java 种子之前**，所以想改 Java 种子的值必须改代码，针对这些行的 `UPDATE` 迁移在空库上是空操作。
3. **分区值取代全局**：`t_zone` 的规则字段非空即取代全局；写成 `min(全局, 分区)` 会让所有"比默认更宽"的分区值变成永不生效的死配置。
4. **处罚阶梯的"方向"**：加分（周恢复/连续主动结束/申诉回补）与扣分走**两个函数**——扣分可在同档延长禁约期，加分**只能降档或解除**，绝不能把恢复时刻往后推。
5. **迁移前滚**：不写 down 脚本；每版脚本幂等可重跑，出问题用新版本修正；`backend/data/` 可删库重建（种子幂等）。

### 1.6 目录结构

```
LibraryReservation/
├─ backend/                 # Spring Boot 后端 + 打进 static 的前端产物
│  ├─ src/main/java/com/libseat/{controller,service,repository,entity,dto,enums,config,scheduler,seed,common,security}
│  ├─ src/main/resources/
│  │  ├─ application*.yml   # h2（默认）/ mysql / test
│  │  ├─ db/migration/{h2,mysql}/V1..V24__*.sql
│  │  └─ static/            # 前端构建产物（dist 复制进来）
│  └─ src/test/java/        # 34 个测试类（单元 + 接口 + 迁移/并发/账本一致性）
├─ frontend/                # React + Vite
│  ├─ src/{pages,layouts,components,api,store,hooks,router,styles,constants}
│  └─ dist/                 # 构建产物
├─ docs/                    # PRD / 设计 / 任务 / README / 本报告 / api-smoke.ps1 / mysql-acceptance.ps1
└─ .workbuddy/              # 回归证据（本地保留，不进 git）
```

---

## 2. 数据库说明

### 2.1 迁移体系（Flyway）

- **版本链**：`V1__init` … `V24__peak_degrade_rule`，共 **24 版**，`db/migration/h2` 与 `db/migration/mysql` 两棵树**逐版对应**（数据语句做过逐字对账）。
- **配置**：`ddl-auto=none`（建表权只在 Flyway）；`baseline-on-migrate=true`、`baseline-version=1`（老库平滑接入）；`spring.sql.init.mode` 已停用（避免与 Flyway 双跑）。
- **双库验证**：H2 —— 空库冷启动 `applied 24 → v24`、二次启动 `validated 24 + up to date + 种子幂等`、两次快照逐字节相同；**MySQL 8.0.40 —— 2026-10-13 首次真执行通过**（`flyway_schema_history` 成功记录 24 条，三档窗口落值正确；过程中发现并修复了 pom 缺 `flyway-mysql` 导致 "Unsupported Database: MySQL 8.0" 的缺陷）。
- **老库兼容**：V1 时代的库（无 Flyway 历史）带 `baseline-on-migrate` 增量升级到 v24，规则类字段与新库逐值一致。

### 2.2 表清单（24 张，按域分组）

| 域 | 表 | 说明 |
| --- | --- | --- |
| 账户 | `t_user` | 用户（角色 STUDENT/TEACHER/LIBRARIAN/SYS_ADMIN），`student_no`（`V22` 回填）、爽约计数、状态 |
| 空间 | `t_floor` | 楼层（1F/2F/3F） |
| | `t_zone` | **分区 = 规则载体**：分区类型、最大时长、单日累计、可提前天数、允许讨论/暂离、暂离上限与次数、组队开关、可见/可用角色；`V4` 建表、后续版本补列 |
| | `t_seat` | 座位（行/列/序号/基础状态）；`zone_id`、`table_id` 由后续迁移补列 |
| | `t_table` | 桌位（按桌组队的物理单元，`seat_count`；**B12：Java 种子会为研讨区建 1 张 4 座桌**） |
| 时间 | `t_open_profile` | 按日型的开放档案（WORKDAY/WEEKEND/HOLIDAY/EXAM），`open_start/open_end` 为**格号**（15/45、16/44、14/46） |
| | `t_open_calendar` | 按日期的覆盖（某天闭馆/改窗口） |
| | `t_peak_window` | 高峰窗口（作用域：全馆/楼层/分区；压缩单次时长、提前放号） |
| | `t_zone_peak_state` | 分区高峰实时状态（`ratio` 与有效时段，供大屏与降级判定） |
| 预约 | `t_reservation` | 预约主单（状态：CONFIRMED/CHECKED_IN/COMPLETED/CANCELLED/NO_SHOW/VIOLATED/ADMIN_CANCELLED） |
| | `t_reservation_slot` | **占用唯一来源**：每格一行；`uk_slot_seat_date_idx(seat_id,reserve_date,slot_index)` 兜底并发 |
| 暂离 | `t_leave_reason` | 原因字典（`max_min`/`daily_limit`/`need_note`；`V21` 增 `relaxable` 标记可被分区放宽） |
| | `t_leave_record` | 暂离记录（计划/实际返回时间、状态；`open_flag` 配合 NULL 唯一性做"同预约同时只允许一次进行中"） |
| 信用 | `t_violation` | 违规账本（类型、扣分、**证据 JSON**、状态、撤销信息） |
| | `t_credit_log` | 信用流水（唯一写入口，`balance_after` 与总分对账） |
| | `t_penalty` | 处罚（类型/起止/状态；回补只降档不延长） |
| | `t_appeal` | 申诉（事由、状态、裁定人/角色、答复） |
| 组队 | `t_group` | 队伍（按桌整单：楼层/分区/桌/日期/起止格、邀请码与过期、锁定时间、状态） |
| | `t_group_member` | 成员（队长/成员、邀请状态、座位、出席状态） |
| 高峰/候补 | `t_waitlist` | 候补（`active_flag` 保证同一人同一时段只有一条生效记录） |
| 巡查/通知 | `t_patrol_task` | 巡查任务（疑点类型/级别/证据/状态/处置人；`open_flag` 控制"同一座位同时只有一个未处置任务"） |
| | `t_notice` | 站内通知（类型、引用对象、已读、过期） |
| 系统 | `t_rule_config` | 规则中心（全局键值 + 分组 + 可编辑性 + 描述） |
| | `t_audit_log` | 关键操作审计（人/动作/目标/详情） |

> 说明：部分列来自后续迁移（`ALTER TABLE ... ADD COLUMN`），例如 `t_seat.zone_id/table_id`、`t_user.student_no`、`t_leave_reason.relaxable`；上表按“最终形态”列出。

### 2.3 关键约束与不变式

1. **同一座位同一时段一人**：`t_reservation_slot` 唯一索引兜底（并发下由数据库拒绝，而不是"先查后插"）。
2. **同一学生同一时段不可重复预约**（含跨分区）：服务端按用户做区间重叠校验（`4092`）。
3. **信用分守恒**：`sum(t_credit_log.delta) == t_user.credit_score`，任何扣分/加分都必须写流水。
4. **占用只来自格子**：座位状态（FREE/RESERVED/OCCUPIED/暂离中…）均由预约格子实时派生，取消立即释放。
5. **NULL 唯一性技巧**：`t_leave_record.open_flag`、`t_waitlist.active_flag`、`t_patrol_task.open_flag` —— 用"生效时为常量、失效时为 NULL"配合唯一索引，实现"同一对象同时只允许一条进行中记录"。
6. **网格与窗口分离**：`SLOT_INDEX_*`（格号存在性）与 `DEFAULT_WINDOW_*`（默认开窗）是两组常量，校验以**当日档案**为准。

### 2.4 种子数据

- **四类账号**：`admin/admin123`（系统管理员）、`librarian1/123456`（馆员）、`teacher1/123456`（教师）、`student1~3/123456`（学生）；另有 `cap*/smoke*` 之类只由冒烟脚本临时注册的账号。
- **演示态（README §6 口径）**：`student1` 50 分、停 3 天（至 2026-10-09）；`student2` 40 分、停 7 天（至 2026-10-13）；`student3` 90 分、可正常预约——用于演示"受限 / 申诉 / 回补"。
- **规则种子**：分区矩阵、开放档案（三档窗口）、暂离原因、高峰窗口、全局规则值（含"单日累计 360 分钟 / 考试周 480 分钟 / 教师单次 360 分钟"等）。
- **幂等**：重启只会看到一条"种子数据已存在，跳过初始化"；桌位种子按"该分区还没有任何桌"判重。

### 2.5 方言差异与运维

- H2（默认，文件库 `./data/library`）用于演示与测试；MySQL 用 `--spring.profiles.active=mysql`，建库语句与连接串见 `application-mysql.yml`（占位凭据 `root/root` 需按环境替换）。
- 一键验收脚本：`powershell -File docs\mysql-acceptance.ps1 -SqlUser <账号>`（密码现场输入、只走环境变量、默认专用库 `library_seat_v2check`，不 DROP 任何东西）。
- 演示库当前**不要**直接当生产库：它含大量回归产生的历史数据（这是故意的，用于演示与回归）。

---

## 3. 运行与部署

```bash
# 后端（8081；本机 8080 被宿主占用，一律用 8081）
cd backend && mvn -o package -DskipTests
java -jar target/library-seat.jar --server.port=8081

# 前端（开发态）
cd frontend && npm install && npm run dev

# 前端（产线：构建后复制进 jar）
cd frontend && npm run build
cp -r dist/assets dist/index.html dist/favicon.svg ../backend/src/main/resources/static/
cd ../backend && mvn -o package -DskipTests   # 重新打包即得单端口 jar
```

- 站点：`http://localhost:8081/`（`/admin/**`、`/staff/**`、`/m/**` 深链可直接刷新）。
- 冒烟：`powershell -File docs\api-smoke.ps1 -Base http://localhost:8081`（期望末行 `PASS 104 / FAIL 0 / SKIP 0`；窗口外第 23 步会合成 1 条 SKIP，属时刻口径）。
- 测试：`cd backend && mvn -o clean test`（期望 `Tests run: 298, Failures: 0, Errors: 0, Skipped: 0`）。

---

## 4. 质量与验收（实测数字）

| 项 | 结果 | 说明 |
| --- | --- | --- |
| 后端测试 | **298 / 0 / 0，Skipped 0** | `clean test`（重编译），多种子隔离，含并发/迁移/账本一致性用例 |
| API 冒烟 | **104 / 0 / 0** | 四角色 × 三命名空间 + V2 全链路；演示库与**全新空库**各跑一遍；脚本自己建的预约自己收尾（跑完无残留） |
| 深链刷新 | **35 / 35**（+未登记路径 404） | 校验脚本从 `WebConfig` 白名单自读，不靠手数 |
| 浏览器控制台 | **JS error 0，网络层 error 0** | 认证后 6 屏；favicon 404 已修 |
| 端到端链路 | 4/4 | 预约→签到→暂离→返回→结束；爽约→扣分→受限→申诉→回补；组队整桌→组长签到→缺席保留；满座排队→释放→offer→确认 |
| 数据库 | H2 与 **MySQL 8.0.40 双双执行通过** | 空库冷启动、二次启动幂等、迁移库可用、逐格占用抽样 |

回归证据（脚本、逐次回执、偏差登记表）在 `.workbuddy/regression-v2-s7/`（本地保留，不进 git；`S7-24-GATE.md` 是总表）。

---

## 5. 已知限制与后续

1. **无推送通道**：通知是站内 + 轮询，页面关闭时收不到（产品层面已声明）。
2. `application-mysql.yml` 里是**占位凭据**（`root/root`），部署时请用环境变量覆盖（`SPRING_DATASOURCE_*`）而不是改文件。
3. 演示库含回归历史；要做干净演示请删 `backend/data/` 重启（种子会自动重建；MySQL 侧换空库）。
4. 高峰降级目前是"超载返回 4291 + 候补"，未实现"动态放号策略"等进阶调控。
5. 一个历史提交（`21cce3d`）的说明文字与当时事实不符，已在校正文档与证据中更正；未改写 git 历史（无远程、改动会牵动 13 个提交）。
