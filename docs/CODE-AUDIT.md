# 代码冗余审计报告

> 日期：2026-09-20 ｜ 范围：`backend/src`（54 Java + 资源）、`frontend/src`（35 文件）
> 原则：**只报告不改变现有功能与行为的精简项**；对"看似冗余实则必要"的内容明确标注为**不可删**，避免误伤。

---

## 0. 审计方法与局限

| 手段 | 用途 |
| --- | --- |
| `mvn dependency:analyze` | 后端依赖声明与实际使用的偏差 |
| 逐符号 Grep 交叉验证 | 区分「主代码引用 / 仅测试引用 / 零引用」——这是判定死代码的关键 |
| 人工审查 | 关键文件逐行读，识别重复实现与可简化结构 |
| 依赖清单 × import 交叉核对 | 前端每个 dependency 是否真的被使用 |

**未覆盖的范围（如实说明）**：
1. **CSS 类名交叉核对未做** —— `global.css` / `seat.css` 中可能存在未被任何 JSX 引用的类，机械化比对成本高，本轮未做。
2. **DTO 字段级冗余未逐个核对** —— 各 `*DTO` 的字段是否全部被前端消费，需前后端字段全量映射，本轮只做了契约层面抽查。
3. **测试代码内部的重复模式未深挖** —— 9 个测试类之间可能有可提取的公共夹具，但测试代码的"重复"通常优于"过度抽象"。

---

## 1. 结论速览

| 类别 | 数量 | 风险 | 建议 |
| --- | --- | --- | --- |
| A. 死代码（零引用导出） | **8 处** | 极低 | 建议删除 |
| B. 重复逻辑 | **3 处**（其中 1 处不建议改） | 低~中 | 建议合并 2 处 |
| C. 可简化结构 | **2 处** | 极低 | 可选 |
| D. 多余的依赖 | **0 处** | — | **不可删**（分析器误报） |
| E. 工作产物残留 | 2 类 | 无 | 建议清理 |

---

## 2. A 类：死代码（8 处）

### A1–A3 后端常量（`common/BizConstants.java`）

| 编号 | 位置 | 判定理由 |
| --- | --- | --- |
| A1 | `OPEN_HOUR_START`（:17） | 主代码与测试**零引用**。同义的 `SLOT_INDEX_MIN = 8` 才是实际被使用的那一个 |
| A2 | `MAX_DURATION`（:32） | 零引用。`validDuration()` 用的是 `ALLOWED_DURATIONS`，而 `List.of(1,2,3,4)` 的最大值已经表达了"上限 4h" |
| A3 | `TIME_PATTERN`（:56） | 零引用。全项目中只有 `DATE_FORMATTER` / `DATETIME_FORMATTER` 两个 formatter，没有任何地方需要 `HH:mm` 的格式化器 |

**潜在影响**：
- 运行时影响：**无**（未被引用的常量不参与任何逻辑）。
- 真正的成本是**认知误导**：`MAX_DURATION = 4` 会让人以为"时长上限由这个常量控制"，但实际由 `ALLOWED_DURATIONS` 控制。两处表达同一约束却没有关联，改动时极易只改一处。
- **与 V2 的冲突**：PRD-V2 中「时长上限」是**按分区可配**的（静音区 4h / 研讨区 3h / 电子阅览区 2h / 教师 6h）。`MAX_DURATION` 这种全局硬编码常量在 V2 下必然废弃，现在删除可避免它被误用。

**建议**：直接删除三个常量。
- `OPEN_HOUR_START` → 如未来 V2 需要开馆时间，应改为**配置项**而非常量。
- `MAX_DURATION` → 删除后，`ALLOWED_DURATIONS` 成为时长约束的**唯一来源**。若想保留语义，改为在 `ALLOWED_DURATIONS` 上补注释：`/** 可选时长；上限即本列表最大值 4 小时（V2 将由分区规则覆盖） */`。
- `TIME_PATTERN` → 删除。

> ⚠️ 已被测试引用的只有 `SLOT_COUNT`(:69) 与 `DEFAULT_DURATION`(:70)（`BizConstantsTest`），这两个在主代码中也在使用，**保留**。

### A4–A8 前端零引用导出

| 编号 | 位置 | 判定理由 | 潜在影响 | 建议 |
| --- | --- | --- | --- | --- |
| A4 | `constants/index.js:69` `SLOT_COUNT` | 全项目零引用。实际使用的是 `SLOT_INDEX_MIN/MAX` 与 `buildFallbackSlots()` 的循环 | 无。但与后端 `BizConstants.SLOT_COUNT` 同名，**容易误以为前后端已对齐**（实际前端从不读它） | 删除 |
| A5 | `utils/datetime.js:12` `DATETIME_FORMAT` | 零引用。前端展示时间直接由 `nowTimeText()` 等函数格式化，没有用到完整 datetime 模式的场景 | 无 | 删除 |
| A6 | `utils/datetime.js:34` `isBookableDate()` | 零引用。日期窗口校验实际由两处完成：`getBookableDates()` 生成列表给 `DatePicker.disabledDate`（`SeatMap.jsx:196`）+ 后端权威校验 | 无功能影响。但**留下一个诱导**：后来者可能以为需要前端再校验一次 | 删除。若将来要做"非法日期提示"，再从 `getBookableDates()` 派生 |
| A7 | `api/reservation.js:29` `fetchReservation(id)` | 零引用。没有任何页面展示"单条预约详情"（`MyReservations` 直接渲染列表项） | 无 | 删除。后端 `GET /api/reservations/{id}` 接口保留（它是权限校验用例的靶子） |
| A8 | `store/AuthContext.jsx:116` `toastError()` | 零引用。它的 JSDoc 写着"避免多处重复 import antd message"，但实际所有页面都直接 `import { message } from 'antd'` —— **这个封装没有被采纳** | 无。但注释与实际不符，会误导 | 删除（连同注释）。若确实想统一错误提示，应在 `request.js` 拦截器里统一（目前已如此） |

**统一说明**：以上均为**内部模块导出**，项目无外部消费者，删除不构成 API 破坏性变更。

---

## 3. B 类：重复逻辑（3 处）

### B1 `AdminLayout` 的菜单高亮逻辑与菜单定义重复 ⭐ 建议修

**位置**：`frontend/src/layouts/AdminLayout.jsx`
- `:27-33` 定义 `menuItems`（5 项，key 即路径）
- `:36-46` 用 5 个 `else if (pathname.startsWith(...))` **again 描述了同一组路径**

```js
// 现状：路径信息写了两遍
const menuItems = [
  { key: '/admin/statistics', label: '数据统计' },
  { key: '/admin/floors',     label: '楼层管理' },
  // ...
];
let selectedKey = '/admin/statistics';
if (pathname.startsWith('/admin/floors')) { selectedKey = '/admin/floors'; }
else if (pathname.startsWith('/admin/usage')) { selectedKey = '/admin/usage'; }
// ... 还有 3 个分支
```

**判定理由**：`menuItems` 的 `key` 本身就是路径前缀，`selectedKey` 完全可以从同一份数据推导出来，不需要第二处硬编码。

**潜在影响**：这是**真实维护风险**——新增一个菜单项（例如 V2 的"巡查任务"）时，如果只加了 `menuItems` 而忘了加 `else if`，副作用是**菜单高亮停留在"数据统计"**，页面内容却是新页面。这类 bug 不会报错，只能靠肉眼发现。

**建议改法**（行为完全等价）：
```js
// 提到模块作用域：避免每次渲染重建数组
const MENU_ITEMS = [
  { key: '/admin/statistics', icon: <BarChartOutlined />, label: '数据统计' },
  { key: '/admin/floors',     icon: <DatabaseOutlined />,  label: '楼层管理' },
  { key: '/admin/usage',      icon: <AppstoreOutlined />,  label: '使用情况' },
  { key: '/admin/reservations', icon: <ProfileOutlined />, label: '预约记录' },
  { key: '/admin/users',      icon: <TeamOutlined />,      label: '用户管理' },
];

// 组件内：由同一份数据推导选中项
const selectedKey = MENU_ITEMS
  .map((item) => item.key)
  .find((key) => pathname.startsWith(key)) || '/admin/statistics';
```
**等价性验证**：逐个路径比对过——`/admin/statistics`、`/admin/floors`、`/admin/floors/1/seats`、`/admin/usage`、`/admin/reservations`、`/admin/users` 命中结果与原 if-else **完全一致**；无匹配时（如 `/admin`）同样回落到 `'/admin/statistics'`。各 key 互不为前缀，`find` 的顺序不影响结果。

**附带收益**：`MENU_ITEMS` 提到模块外，避免每次渲染生成新数组导致 `Menu` 收到变化的 props。

### B2 `slotLabel` 与 `buildTimeRange` 实现等价 ⭐ 建议合并

**位置**：`frontend/src/utils/datetime.js`
```js
// :49
export function slotLabel(slotIndex) {
  const start = String(slotIndex).padStart(2, '0');
  const end = String(slotIndex + 1).padStart(2, '0');
  return `${start}:00-${end}:00`;
}
// :72
export function buildTimeRange(startSlot, duration) {
  const start = String(startSlot).padStart(2, '0');
  const end = String(startSlot + duration).padStart(2, '0');
  return `${start}:00-${end}:00`;
}
```

**判定理由**：`slotLabel(i)` 与 `buildTimeRange(i, 1)` **返回值逐字相同**。`slotLabel` 是 `buildTimeRange` 在 `duration = 1` 时的特例，属典型重复实现。

**潜在影响**：两处维护同一格式。若将来时段标签要改成 `14:00~15:00`（改分隔符）或加"约"字，只改一处就会造成**同一页面两种格式**（`SeatMap` 的时段下拉用 `slotLabel`，预约抽屉预览用 `buildTimeRange`）。

**建议改法**：
```js
export function slotLabel(slotIndex) {
  return buildTimeRange(slotIndex, 1);
}
```
保留 `slotLabel` 这个名字（调用点多、语义更贴合"时段"），但实现委托给泛化版本。**外部行为零变化**。

### B3 `BizConstants.slotStart` 与 `slotEnd` 方法体相同 —— **不建议改**

**位置**：`backend/src/main/java/com/libseat/common/BizConstants.java:112-125`

```java
public static LocalDateTime slotStart(LocalDate date, int slotIndex) {
    return LocalDateTime.of(date, LocalTime.of(slotIndex, 0));
}
public static LocalDateTime slotEnd(LocalDate date, int slotIndex) {
    return LocalDateTime.of(date, LocalTime.of(slotIndex, 0));
}
```

**判定**：**这是"语义别名"，不是冗余，建议保持现状。**

理由：
1. 二者取的都是**整点**（时段是左闭右开区间 `[start, end)`，"21 时段的结束"就是 22:00 整点），实现相同是**业务语义的正确表达**，而非复制粘贴的失误。
2. 调用处可读性明显更好：`BizConstants.slotEnd(date, r.getEndSlot())` 一眼可知语义；若合并成一个 `slotPoint()`，调用处要脑补它是起点还是终点。
3. 强行合并（例如让 `slotEnd` 调 `slotStart`）收益仅为 1 行代码，却增加了间接层，属于**为消除重复而降低可读性**的负优化。

> 同理，SQL 中也没有"开始"和"结束"两种不同的时间计算——这条规则本身就不该被"优化"。

---

## 4. C 类：可简化结构（2 处，可选）

### C1 `SeatMap` 的楼层信息兜底请求

**位置**：`frontend/src/pages/SeatMap.jsx:84-100`

组件额外调用 `fetchFloor(floorId)` 拿到 `floorInfo`，但 `floorInfo` 只在**一个地方**被使用（`SeatMap.jsx:179`）：
```js
{ title: mapData ? mapData.floorName : (floorInfo ? floorInfo.name : `楼层 ${floorId}`) }
```
即：**仅当 `mapData` 尚未加载成功时**，才用于面包屑显示楼层名。而 `mapData` 一旦返回就自带了 `floorName`。

**判定**：严格说这不是"死代码"（存在边缘场景），属于**低价值简化**。

**潜在影响**：每次进入页面多发一个 HTTP 请求；换来的是"加载中的一瞬间面包屑显示真实楼层名而不是 `楼层 1`"。

**建议**（按偏好二选一）：
- **保守**：保留现状。加载态显示真实楼层名对体验有正向价值。
- **精简**：删除 `fetchFloor` 调用与 `floorInfo` state，面包屑兜底改为 `楼层 ${floorId}`。省一个请求，代价是加载瞬间的显示略粗糙。

### C2 `AdminLayout` 的菜单数组定义在组件内

已并入 **B1** 的改法中（提到模块作用域）。单独看，它只是"每次渲染重建数组"，`AdminLayout` 渲染频率极低，**实际性能影响可忽略**——列在这里只为说明它与 B1 的修复是同一处改动。

---

## 5. D 类：依赖 —— **0 处冗余，且存在误报陷阱**

### 5.1 后端：`mvn dependency:analyze` 的结论**不可直接采信**

分析器报告：
```
[WARNING] Unused declared dependencies found:
   org.springframework.boot:spring-boot-starter-web:jar:3.2.5:compile
   org.springframework.boot:spring-boot-starter-data-jpa:jar:3.2.5:compile
   org.springframework.boot:spring-boot-starter-validation:jar:3.2.5:compile
   com.h2database:h2:jar:2.2.224:runtime
   com.mysql:mysql-connector-j:jar:8.3.0:runtime
   io.jsonwebtoken:jjwt-impl:jar:0.11.5:runtime
```

**逐条判定：全部为误报，一个都不能删。**

| 依赖 | 为何被误判 | 删掉的后果 |
| --- | --- | --- |
| `starter-web` / `starter-data-jpa` / `starter-validation` | starter 是**聚合 POM**，本身没有类。代码实际 import 的是它传递带来的 `spring-web`、`spring-webmvc`、`hibernate-core`、`hibernate-validator`——所以这些传递依赖反而出现在 `Used undeclared dependencies` 里。这是分析器的经典盲区 | 编译立刻失败（找不到 `@RestController`、`@Entity`、`@Valid`） |
| `h2` / `mysql-connector-j` | `runtime` 作用域，**编译期无需引用**，通过 `Class.forName` 反射加载 | 应用启动即报 `Cannot load driver class` |
| `jjwt-impl` | 同上，`runtime` 作用域，由 `jjwt-api` 通过 SPI 反射装配 | 签发/校验 JWT 时抛 `UnsupportedJwtException` 或找不到实现类 |

**正确做法**：不要为消除该警告而删依赖。若想让它闭嘴，可用 `maven-dependency-plugin` 的 `ignoredUnusedDeclaredDependencies` 显式忽略这三类（starter / runtime 驱动 / SPI 实现），但**收益仅为日志整洁**，可选。

**其他后端依赖核查**：
- `spring-security-crypto` —— 实际只用 `BCryptPasswordEncoder`。看起来"为一行代码引一个依赖"，但这是**刻意设计**：避免引入完整 `spring-boot-starter-security` 带来默认登录页与 CSRF 干扰（DESIGN §1.2 已论证）。**保留**。
- `lombok` —— scope 为 `provided`，不会打进产物（`spring-boot-maven-plugin` 也显式 exclude 了）。**保留**。
- `jjwt` 三件套（api/impl/jackson）—— 缺一不可（jackson 供 payload 序列化）。**保留**。

### 5.2 前端：9 个 dependency 全部在用

| 依赖 | 使用位置 |
| --- | --- |
| `antd` | 全站组件 |
| `@ant-design/icons` | 8 个文件 |
| `axios` | `api/request.js` |
| `dayjs` | 6 个文件 |
| `react-router-dom` | 12 个文件 |
| `react` / `react-dom` | 入口 |
| `echarts-for-react` | `pages/admin/Statistics.jsx:4` |
| **`echarts`** | ⚠️ **代码中没有任何 `import ... from 'echarts'`，但必须保留** |

**关于 `echarts` 的说明**：它是 `echarts-for-react` 的 **peer dependency**——`echarts-for-react` 只提供 React 包装层，真正渲染图表的 `echarts` 由使用方提供。删除后 `npm run build` 可能仍能通过（如果 node_modules 里还残留），但**全新克隆的仓库 `npm ci` 后会因缺少 peer dep 直接构建失败**。

**结论**：前端依赖无需任何增删。

---

## 6. E 类：工作产物残留（非代码，建议清理）

| 位置 | 内容 | 判定 |
| --- | --- | --- |
| `.workbuddy/warmup/`（`pom.xml` + `log.txt`） | 我为**预热 Maven 依赖缓存**临时创建的假 pom，任务已完成 | 无用途，可删 |
| `.workbuddy/*.log`、`*.txt` | 构建日志、测试日志、冒烟输出（`build2.log`、`qa-*.log`、`patch-*.log`、`fe-build*.log` 等约 20 个） | 排查用临时产物，交付前可清 |
| `backend/data/` | H2 数据库目录（当前为空，已清理过验证库） | **保留**（`.gitignore` 已忽略，首次启动自动生成） |

> `.workbuddy/` 目录本身**不要删除**——它承载项目记忆（`memory/`）与团队运行态数据。只清理其中的临时日志即可。

---

## 7. 执行建议

**推荐顺序**（风险从低到高，每步都可独立验证）：

| 步骤 | 动作 | 验证方式 |
| --- | --- | --- |
| 1 | 删除 A 类 8 处死代码 | `mvn clean test`（应 120/120）+ `npm run build`（应 EXITCODE=0） |
| 2 | 修复 B1（AdminLayout 菜单推导） | 手工点击 5 个菜单项，确认高亮正确 |
| 3 | 合并 B2（slotLabel 委托） | 进座位平面图页，确认时段下拉与预约预览的文本格式未变 |
| 4 | （可选）处理 C1 | 观察加载瞬间面包屑显示 |
| 5 | 清理 E 类工作产物 | 无 |

**总改动量**：约 8 处删除（合计 < 30 行）+ 2 处重构（约 15 行），**不涉及任何业务逻辑、接口契约与数据结构**。

**关键约束**：B3 明确不建议改；D 类依赖一个都不要动。
