# 启动与演示（V2）

一句话：**单端口 jar 就是整站**（前端产物已打进 jar），本机演示一律用 **8081**（8080 被宿主进程占用，别去动它）。

## 1. 工具链（Windows，路径含空格必须加引号）

| 用途 | 路径 |
| --- | --- |
| JDK | `D:\JDK SE-21`（先 `$env:JAVA_HOME`） |
| Maven | `C:\Users\18500\.workbuddy\binaries\maven\apache-maven-3.9.9\bin\mvn.cmd` |
| Node/npm | `D:\nodejs\npm.cmd` |

```powershell
$env:JAVA_HOME = 'D:\JDK SE-21'
$mvn  = 'C:\Users\18500\.workbuddy\binaries\maven\apache-maven-3.9.9\bin\mvn.cmd'
$npm  = 'D:\nodejs\npm.cmd'
```

## 2. 跑测试

```powershell
cd D:\Project\LibraryReservation\backend
Remove-Item -Recurse -Force target\test-classes -ErrorAction SilentlyContinue
& $mvn -o test          # 期望：Tests run: 280, Failures: 0, Errors: 0, Skipped: 0
```

> ★ 先删 `target/test-classes`：Maven 的增量编译会拿旧的 class 跑，出过"假绿"。
> ★ 用例共享同一个内存库（`jdbc:h2:mem:qa_test`，整个 JVM 一份），所以用例必须自清理，
>   计数类断言要写差值而不是绝对值。

## 3. 构建单端口 jar

```powershell
cd D:\Project\LibraryReservation\frontend
& $npm run build
cd D:\Project\LibraryReservation
Remove-Item -Recurse -Force backend\src\main\resources\static\assets, backend\src\main\resources\static\index.html -ErrorAction SilentlyContinue
Copy-Item -Recurse -Force frontend\dist\* backend\src\main\resources\static\
cd backend
& $mvn -o clean package -DskipTests
```

> ★ 打包前先停掉正在跑的 8081（`target/library-seat.jar` 被占用会让 `clean` 失败）：
>   `netstat -ano | Select-String ':8081'` 拿 PID → `Get-CimInstance Win32_Process -Filter "ProcessId=<pid>"` 核对命令行
>   确实是本项目的 `java -jar`（**8080 的宿主进程绝不允许停**）→ 再 `Stop-Process`。
> ★ 核对产物里的前端确实是刚构建的那份：比对 `frontend/dist` 与 jar 内 `BOOT-INF/classes/static/` 的逐文件 MD5。

## 4. 启动与演示账号

```powershell
cd D:\Project\LibraryReservation\backend
& "$env:JAVA_HOME\bin\java.exe" -jar target\library-seat.jar --server.port=8081
```

浏览器打开 `http://localhost:8081`。Flyway 会自动把库迁到最新版本（当前 **v14**）。
演示库是 H2 文件库 `backend/data/library`（首次启动由 `V1__baseline.sql` 等种子建起来）。

| 账号 | 口令 | 角色 | 看什么 |
| --- | --- | --- | --- |
| `student1..3` | `123456` | 学生 | 预约、签到、暂离、组队、候补、信用与申诉、通知铃铛 |
| `teacher1` | `123456` | 教师 | 教师专区与更长时长上限 |
| `librarian1` | `123456` | 馆员 | `/staff` 作业台、`/staff/patrol` 巡查看板、`/staff/dashboard` 监控大屏、`/staff/appeals` 申诉复核 |
| `admin` | `admin123` | 系统管理员 | `/admin/**`：空间与规则配置、`/admin/rules/history` 规则变更历史、`/admin/export` 台账导出 |

移动端壳（同一份后端，窄屏布局）：`http://localhost:8081/m/home`，底部五格 Tab。
PC 端顶栏有"手机版"入口，移动壳右上角有"PC 版"出口。

## 5. 冒烟与验收脚本

```powershell
cd D:\Project\LibraryReservation
powershell -ExecutionPolicy Bypass -File docs\api-smoke.ps1 -Base http://localhost:8081
# 期望最后一行：冒烟脚本执行完毕：PASS 70 / FAIL 0 / SKIP 0，退出码 0
```

脚本自带期望值与退出码（有 FAIL 就 `exit 1`），覆盖四角色×三命名空间权限矩阵、规则改值与历史、
账本一致性、分区规则、预约闸门、签到→暂离→返回→结束全链路、信用与违规、巡查看板、
监控大屏（含耗时与"楼层加总=全馆座位"的口径自洽）、四类台账 CSV（含 UTF-8 BOM 与审计留痕）、V2 深链。

> ★ 时间相关的链路（签到/暂离）在闭馆后按 `SKIP` 计并打印出来 —— 夜里跑出来的绿是"跳过换来的绿"。
> ★ 这个 `.ps1` 必须保留 **UTF-8 BOM**，否则 PowerShell 5.1 会把中文注释与字面量读成乱码。

## 6. 演示脚本建议（五分钟讲完 V2）

1. `student2` 登录 → `/floors` 看分区余量与"高峰"角标 → 进平面图选座（暂离中的格子显示"预计返回"）。
2. 我的预约：签到 → 登记暂离（看到应归时刻）→ 我已返回 → 结束使用；顺带演示"按钮置灰必带原因"。
3. `/credit`：违规明细带可读证据 → 提交申诉；`librarian1` 在 `/staff/appeals` 裁定成立，学生侧收到站内通知。
4. `/groups/create` 建队邀请同学确认，锁定后整桌出票；组长签到＝全队签到。
5. 满座时段演示候补排队；`/staff/patrol` 演示"机器只登记疑点、释放必须由馆员填原因"，处置后学生收到站内通知。
6. `/staff/dashboard` 大屏 + `/admin/export` 导一份台账（提醒：导出留审计）+ `/admin/rules` 改一个阈值并当场生效，`/admin/rules/history` 看前后值。
7. 手机视角：把窗口拉到 375px 或直接开 `/m/home`，三次点击完成预约。

## 7. 数据与运维提示

- 规则全部可在 `/admin/rules` 改，**改后立即生效、无需重启**；变更前后值在 `/admin/rules/history`。
- 已知的产品口径请读 `docs/DESIGN.md` §0.1（V2 生效范围）与 `docs/DESIGN-V2.md`；
  PRD-V2 §12 二十六条基准的逐条映射与偏差登记在 `.workbuddy/regression-v2-s7/S7-24-GATE.md`。
- 本系统**没有推送通道**：站内通知 = `t_notice` + 前端轮询，页面关着收不到提醒（UI 上已明说）。
