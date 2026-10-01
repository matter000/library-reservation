# =====================================================================
# 图书馆座位预约系统 —— 后端接口冒烟脚本（T-BE-10）
# 用法（先启动后端 http://localhost:8080）：
#   powershell -ExecutionPolicy Bypass -File docs\api-smoke.ps1 [-Base http://localhost:8081]
# =====================================================================
param([string]$Base = 'http://localhost:8080')

$ErrorActionPreference = 'Continue'

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null
    )
    $headers = @{ 'Content-Type' = 'application/json' }
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $uri = "$Base$Path"
    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 6 -Compress
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -Body $json
        }
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        $reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
        $text = $reader.ReadToEnd()
        return [PSCustomObject]@{ __httpError = $status; __body = $text }
    }
}

function Show {
    param([string]$Title, [object]$Value)
    Write-Host "----------------------------------------" -ForegroundColor DarkGray
    Write-Host "▶ $Title" -ForegroundColor Cyan
    Write-Host ($Value | ConvertTo-Json -Depth 6)
}

$stamp = Get-Random -Maximum 99999

# ---------- 1. 注册 ----------
Show '1. 注册新用户（后端强制 role=STUDENT）' (Invoke-Api POST '/api/auth/register' @{
    username = "smoke$stamp"; password = '123456'; confirmPassword = '123456'
    realName = '冒烟用户'; phone = '13900001234'; role = 'SYS_ADMIN'
})

# ---------- 2. 重复注册 → 4090 ----------
Show '2. 重复注册（期望 4090）' (Invoke-Api POST '/api/auth/register' @{
    username = "smoke$stamp"; password = '123456'; confirmPassword = '123456'; realName = '冒烟用户'
})

# ---------- 3. 登录（学生） ----------
$stu = Invoke-Api POST '/api/auth/login' @{ username = 'student1'; password = '123456' }
Show '3. 学生登录' $stu
$stuToken = $stu.data.token

# ---------- 4. 登录（管理员） ----------
$adm = Invoke-Api POST '/api/auth/login' @{ username = 'admin'; password = 'admin123' }
Show '4. 管理员登录' $adm
$admToken = $adm.data.token

# ---------- 5. 未登录访问 → 4010 ----------
Show '5. 未登录访问 /api/floors（期望 4010）' (Invoke-Api GET '/api/floors')

# ---------- 6. 越权：学生访问 /api/admin/** → 4030 ----------
Show '6. 学生 Token 访问 /api/admin/floors（期望 4030）' (Invoke-Api GET '/api/admin/floors' $null $stuToken)

# ---------- 6b. V2 四角色权限矩阵 ----------
$tch = Invoke-Api POST '/api/auth/login' @{ username = 'teacher1'; password = '123456' }
$lib = Invoke-Api POST '/api/auth/login' @{ username = 'librarian1'; password = '123456' }
Show '6b. 四角色矩阵（教师/馆员角色 + 命名空间拒绝）' @{
    teacherRole    = $tch.data.user.role
    librarianRole  = $lib.data.user.role
    studentToStaff = (Invoke-Api GET '/api/staff/usage' $null $stuToken).__httpError   # 期望 403
    libToAdmin     = (Invoke-Api GET '/api/admin/floors' $null $lib.data.token).__httpError  # 期望 403
    libToFloors    = (Invoke-Api GET '/api/floors' $null $lib.data.token).code          # 期望 0
}

# ---------- 7. 楼层列表 ----------
$floors = Invoke-Api GET '/api/floors' $null $stuToken
Show '7. 楼层列表' $floors
$floorId = $floors.data[0].floorId

# ---------- 8. 平面图 ----------
$map = Invoke-Api GET "/api/seats/map?floorId=$floorId" $null $stuToken
Show '8. 座位平面图（stats）' $map.data.stats

# ---------- 9. 时段选项 ----------
Show '9. 时段选项条数/首项' @{
    count = (Invoke-Api GET '/api/config/slots' $null $stuToken).data.Count
    first = (Invoke-Api GET '/api/config/slots' $null $stuToken).data[0]
}

# ---------- 10. 预约（用本次新注册用户，避免与种子用户已有预约时段重叠） ----------
# ★ 必须按「目标日期 + 目标时段」查平面图：第 8 步的 map 是「当前时段」的空闲态，
#   拿它选出的座位去约明天 14 点，重复执行时必然撞 uk_slot_seat_date_idx → 4091。
$tomorrow = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
$smokeStart = Get-Random -Minimum 8 -Maximum 20
$targetMap = Invoke-Api GET "/api/seats/map?floorId=$floorId&date=$tomorrow&slot=$smokeStart" $null $stuToken
$freeSeat = ($targetMap.data.seats | Where-Object { $_.displayStatus -eq 'FREE' } | Select-Object -First 1)
$smokeLogin = Invoke-Api POST '/api/auth/login' @{ username = "smoke$stamp"; password = '123456' }
$smokeToken = $smokeLogin.data.token
Show '10. 提交预约' (Invoke-Api POST '/api/reservations' @{
    seatId = $freeSeat.seatId; date = $tomorrow; startSlot = $smokeStart; duration = 1
} $smokeToken)

# ---------- 11. 我的预约 ----------
Show '11. 我的预约（upcoming）' (Invoke-Api GET '/api/reservations/my?tab=upcoming' $null $stuToken)

# ---------- 12. 管理员：楼层列表 / 统计 ----------
Show '12a. 管理员楼层列表' (Invoke-Api GET '/api/admin/floors' $null $admToken)
Show '12b. 统计概览' (Invoke-Api GET '/api/admin/statistics/overview' $null $admToken)
Show '12c. 趋势(7天)条数' (Invoke-Api GET '/api/admin/statistics/trend?days=7' $null $admToken).data.Count
Show '12d. 时段热度条数' (Invoke-Api GET '/api/admin/statistics/slot-heat?days=7' $null $admToken).data.Count
Show '12e. 楼层利用率' (Invoke-Api GET '/api/admin/statistics/floor-usage' $null $admToken).data
Show '12f. 状态分布' (Invoke-Api GET '/api/admin/statistics/status-distribution?days=7' $null $admToken).data

# ---------- 13. 管理员：使用情况 ----------
Show '13. 使用情况（1F 今天）' (Invoke-Api GET "/api/admin/usage?floorId=$floorId" $null $admToken).data.summary

# ---------- 14. 管理员：预约记录 ----------
Show '14. 预约记录' (Invoke-Api GET '/api/admin/reservations?page=1&size=5' $null $admToken).data.total

# ---------- 15. 管理员：批量生成（重复执行应全跳过；起始号随机 + 行起点避开已占用行） ----------
# rowFrom=9 是关键：楼层 1~6 行被种子座位占满，不设行起点时新座位会整批撞坐标被跳过
# （V2 修掉的正是「只校验编号、不校验坐标」导致的同格叠座）。
$batchStart = Get-Random -Minimum 100 -Maximum 900
Show "15a. 批量生成 1x3 @第9行（Z$batchStart 起）" (Invoke-Api POST '/api/admin/seats/batch' @{
    floorId = $floorId; rows = 1; cols = 3; prefix = 'Z'; startNo = $batchStart; zone = 'Z区'
    rowFrom = 9; colFrom = 1
} $admToken).data
Show '15b. 再执行一次（期望 created=0, skippedPositions=3）' (Invoke-Api POST '/api/admin/seats/batch' @{
    floorId = $floorId; rows = 1; cols = 3; prefix = 'Z'; startNo = $batchStart; zone = 'Z区'
    rowFrom = 9; colFrom = 1
} $admToken).data
Show '15c. 冲突预览（不落库）' (Invoke-Api POST '/api/admin/seats/batch-preview' @{
    floorId = $floorId; rows = 1; cols = 3; prefix = 'Z'; startNo = $batchStart
    rowFrom = 9; colFrom = 1
} $admToken).data

# ---------- 16. 管理员：用户列表（手机号脱敏） ----------
Show '16. 用户列表' (Invoke-Api GET '/api/admin/users?page=1&size=5' $null $admToken).data.list

Write-Host "========================================" -ForegroundColor Green
Write-Host "冒烟脚本执行完毕" -ForegroundColor Green
