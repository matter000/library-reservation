<#
.SYNOPSIS
  V2 收口唯一没跑过的一条腿：在**真 MySQL** 上执行 mysql 方言迁移树（V1..V24）并验收。
.DESCRIPTION
  为什么需要这个脚本：仓库里 application-mysql.yml 的占位凭据 root/root 被 1045 拒，
  而 mysql_router MCP 后端在这台机器上直接退出 —— 迁移脚本 `db/migration/mysql/**` 至今只是
  与 h2 版逐字对账过（S7-24-GATE.md §4.4），**从未被执行**。本脚本把"要打的字"减到最少：
  你只需要给一个可用账号，密码只在当前进程里存在（不落盘、不进命令行、不进日志）。
  默认用一个**专用库** library_seat_v2check，绝不碰 library_seat 之外的任何 schema，也不删库。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File docs\mysql-acceptance.ps1 -SqlUser root
.NOTES
  判据只有三条（和门表 §8-1 一致）：Flyway 到 v24、成功记录 24 条、三档窗口 = 15/45、16/44、14/46。
#>
param(
    [Parameter(Mandatory = $true)][string]$SqlUser,
    [string]$Schema = 'library_seat_v2check',
    [int]$Port = 3306,
    [int]$ServerPort = 8085,
    [string]$MysqlCli = 'D:\MySQL\bin\mysql.exe',
    [string]$Java = 'D:\JDK SE-21\bin\java.exe',
    [string]$Jar = 'backend\target\library-seat.jar'
)
$ErrorActionPreference = 'Stop'
$fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { Write-Host ("  [PASS] {0} {1}" -f $name, $detail) -ForegroundColor Green }
    else { $script:fail++; Write-Host ("  [FAIL] {0} {1}" -f $name, $detail) -ForegroundColor Red }
}
if (-not (Test-Path $MysqlCli)) { Write-Host "找不到 mysql.exe：$MysqlCli（用 -MysqlCli 指你的路径）" -ForegroundColor Red; exit 2 }
if (-not (Test-Path $Jar)) { Write-Host "找不到 jar：$Jar（先 mvn -o package -DskipTests）" -ForegroundColor Red; exit 2 }
if (-not (Test-Path $Java)) { Write-Host "找不到 java.exe：$Java" -ForegroundColor Red; exit 2 }

$Secure = Read-Host -Prompt ("MySQL 密码（账号 {0}，只在本进程内存里，不落盘）" -f $SqlUser) -AsSecureString
$Bstr = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($Secure)
try { $Plain = [Runtime.InteropServices.Marshal]::PtrToStringUni($Bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($Bstr) }

# 密码只走 MYSQL_PWD 环境变量：不出现在命令行 argv（进程列表看得 argv 看不见这个）
$env:MYSQL_PWD = $Plain
function Sql($q) {
    & $MysqlCli "--host=127.0.0.1" "--port=$Port" "-u$SqlUser" "-N" "-B" "-e" $q 2>&1
}
Write-Host "== 0. 连通性 ==" -ForegroundColor Cyan
$v = Sql "SELECT VERSION()"
if ($LASTEXITCODE -ne 0) { Write-Host "连不上：$v" -ForegroundColor Red; Remove-Item Env:MYSQL_PWD; exit 2 }
Write-Host "  server = $v"

Write-Host "== 1. 专用库 ==" -ForegroundColor Cyan
Sql "CREATE DATABASE IF NOT EXISTS ``$Schema`` DEFAULT CHARSET utf8mb4" | Out-Null
Check "1.0 建库不报错" ($LASTEXITCODE -eq 0) "(schema=$Schema)"

Write-Host "== 2. 让 Flyway 在 MySQL 上跑 V1..V24 ==" -ForegroundColor Cyan
$env:SPRING_PROFILES_ACTIVE = 'mysql'
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:$Port/$Schema`?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
$env:SPRING_DATASOURCE_USERNAME = $SqlUser
$env:SPRING_DATASOURCE_PASSWORD = $Plain
$log = Join-Path $env:TEMP "mysql-acceptance-boot.log"
Remove-Item Env:MYSQL_PWD
$p = Start-Process -FilePath $Java -ArgumentList @('-jar', $Jar, "--server.port=$ServerPort") `
    -WorkingDirectory (Get-Location).Path -WindowStyle Hidden `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru
try {
    $up = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        if ((Test-Path $log) -and (Select-String -Path $log -Pattern 'Started library-seat|APPLICATION FAILED|Error starting' -Quiet)) { $up = $true; break }
    }
    Check "2.0 应用起来了" $up "(等待 $((($i)+1)*2)s, 日志 $log)"
    $boot = if (Test-Path $log) { Get-Content $log -Raw -Encoding UTF8 } else { '' }
    Check "2.1 Flyway 到 v24" ($boot -match 'now at version v24') `
        ($boot | Select-String -Pattern '(Successfully applied \d+ migrations[^)]*|Schema .* is up to date[^.]*|now at version v\d+)' | ForEach-Object { $_.Matches[0].Value } | Select-Object -First 1)
    $env:MYSQL_PWD = $Plain
    $cnt = Sql "SELECT COUNT(*) FROM ``$Schema``.flyway_schema_history WHERE success = 1"
    Check "2.2 迁移成功记录 = 24 条" ("$cnt" -eq '24') "(实际 $cnt)"

    Write-Host "== 3. 三档开放窗口（PRD §12-1 落值） ==" -ForegroundColor Cyan
    $rows = Sql "SELECT day_type, open_start, open_end FROM ``$Schema``.t_open_profile ORDER BY day_type"
    $map = @{}
    foreach ($line in $rows) { $c = "$line" -split "`t"; if ($c.Count -ge 3) { $map[$c[0]] = "$($c[1])/$($c[2])" } }
    Check "3.1 WORKDAY = 15/45" ($map['WORKDAY'] -eq '15/45') "(实际 $($map['WORKDAY']))"
    Check "3.2 WEEKEND = 16/44" ($map['WEEKEND'] -eq '16/44') "(实际 $($map['WEEKEND']))"
    Check "3.3 EXAM    = 14/46" ($map['EXAM'] -eq '14/46') "(实际 $($map['EXAM']))"
    Write-Host "  HOLIDAY = $($map['HOLIDAY'])（PRD 未给值，保持 V6 原样，只报不判）"

    Write-Host "== 4. 种子与建表规模（顺手看一眼，不判） ==" -ForegroundColor Cyan
    $tables = Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Schema'"
    $users = Sql "SELECT COUNT(*) FROM ``$Schema``.t_user"
    $tables2 = Sql "SELECT COUNT(*) FROM ``$Schema``.t_table"
    Write-Host "  表数=$tables  用户数=$users  桌位数 t_table=$tables2（B12 之后应为 1）"
} finally {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    $Plain = $null; $Secure = $null
}
Write-Host ("========================================") -ForegroundColor Cyan
if ($fail -gt 0) { Write-Host ("mysql 验收未通过：FAIL {0}" -f $fail) -ForegroundColor Red; exit 1 }
Write-Host "mysql 验收通过：迁移树在真 MySQL 上执行完毕，三档窗口落值正确。" -ForegroundColor Green
Write-Host ("清理请你自己执行（脚本不删任何东西）：在 mysql 客户端里跑  DROP DATABASE " + $Schema + ";")
exit 0
