# =====================================================================
# 图书馆座位预约系统 —— 后端接口冒烟脚本（V1 T-BE-10 + V2 S0~S7 全步骤）
#
# 用法（先启动单端口 jar；本机 8080 被宿主进程占用，一律用 8081）：
#   java -jar backend/target/library-seat.jar --server.port=8081
#   powershell -ExecutionPolicy Bypass -File docs\api-smoke.ps1 -Base http://localhost:8081
#
# ★ 脚本带期望值并给出退出码（FAIL>0 → exit 1），不再需要人逐行比对 JSON。
#   时间相关的链路（签到/暂离）在闭馆后按 SKIP 计，既不算通过也不算失败，但一定打印出来
#   —— 夜里跑出来的绿是「跳过换来的绿」，必须看得见。
# ★ 本文件必须保留 UTF-8 BOM：PowerShell 5.1 在非 UTF-8 代码页下按 ANSI 读脚本，
#   没有 BOM 时中文注释与中文字面量会一起变乱码（含期望表里的中文）。
# =====================================================================
param([string]$Base = 'http://localhost:8081')

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
            # ★ 用 -InputObject 而不是走管线：单元素数组经管线会被摊平成对象，
            #   PUT /api/admin/rules 收到 {...} 而非 [{...}] 时报 4001，看着像接口坏了
            $json = ConvertTo-Json -InputObject $Body -Depth 6 -Compress
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

# 4xx/5xx 的响应体是 {code,message} —— Invoke-Api 把它放在 __body 里。
# ★ 冒烟要断言的是**业务码**（4030/4092/4102…），HTTP 状态只是壳；
#   而 PowerShell 5.1 的 Invoke-RestMethod 会把 UTF-8 中文按本地代码页解，
#   所以任何比较都不要依赖中文字面量，只比数字码与 id。
function Biz-Code {
    param([object]$Response)
    if ($null -ne $Response.code) { return $Response.code }
    if ($null -ne $Response.__body) {
        try { return ((ConvertFrom-Json $Response.__body).code) } catch { return $Response.__httpError }
    }
    if ($null -ne $Response.__httpError) { return $Response.__httpError }
    return $null
}

# 4xx 的文案在 __body 里，成功时直接读 .message；两者都返回字符串（可能为空）。
function Biz-Message {
    param([object]$Response)
    if ($null -ne $Response.__body) {
        try { return ([string](ConvertFrom-Json $Response.__body).message) } catch { return '' }
    }
    return [string]$Response.message
}

# 取一个在 [slot, slot+count) **每一格**都空闲的座位。
# ★ 只看起始格是经典坑：4 小时的预约要占 8 格，起始格空、后面被人占了，
#   接口就回 4091「该座位刚被他人预约」——看起来像并发问题，其实是选座选错了。
function Free-Seat {
    param([long]$Floor, [string]$Date, [int]$Slot, [int]$Count, [long]$Zone, [string]$Token)
    $candidates = $null
    for ($i = 0; $i -lt $Count; $i++) {
        $map = Invoke-Api GET "/api/seats/map?floorId=$Floor&date=$Date&slot=$($Slot + $i)" $null $Token
        $free = @($map.data.seats | Where-Object { $_.displayStatus -eq 'FREE' -and $_.zoneId -eq $Zone } |
            ForEach-Object { $_.seatId })
        if ($null -eq $candidates) { $candidates = $free }
        else { $candidates = @($candidates | Where-Object { $free -contains $_ }) }
        if ($candidates.Count -eq 0) { return $null }
    }
    if ($candidates.Count -eq 0) { return $null }
    return $candidates[0]
}

$script:passCount = 0
$script:failCount = 0
$script:warnCount = 0

# ★ 冒烟脚本要有结论，否则它只是一段打印。Expect 把「期望表」写进脚本本体，
#   跑完给出 PASS/FAIL/WARN 计数与退出码：CI 或人复核都不需要再逐行比对 JSON。
function Expect {
    param([string]$Name, [object]$Actual, [object]$Want)
    if ("$Actual" -eq "$Want") {
        $script:passCount++
        Write-Host ("  [PASS] {0} = {1}" -f $Name, $Actual) -ForegroundColor Green
    } else {
        $script:failCount++
        Write-Host ("  [FAIL] {0} 期望 {1}，实际 {2}" -f $Name, $Want, $Actual) -ForegroundColor Red
    }
}

function ExpectIn {
    param([string]$Name, [object]$Actual, [object[]]$Options)
    if ($Options -contains "$Actual") {
        $script:passCount++
        Write-Host ("  [PASS] {0} = {1}（在 {2} 之内）" -f $Name, $Actual, ($Options -join '/')) -ForegroundColor Green
    } else {
        $script:failCount++
        Write-Host ("  [FAIL] {0} = {1}，不在 {2} 之内" -f $Name, $Actual, ($Options -join '/')) -ForegroundColor Red
    }
}

function ExpectNotZero {
    param([string]$Name, [object]$Actual)
    if ("$Actual" -ne '0' -and $null -ne "$Actual") {
        $script:passCount++
        Write-Host ("  [PASS] {0} 未通过校验（code={1}）" -f $Name, $Actual) -ForegroundColor Green
    } else {
        $script:failCount++
        Write-Host ("  [FAIL] {0} 本应被拒，实际 code={1}" -f $Name, $Actual) -ForegroundColor Red
    }
}

function Skip {
    param([string]$Name, [string]$Why)
    $script:warnCount++
    Write-Host ("  [SKIP] {0}：{1}" -f $Name, $Why) -ForegroundColor Yellow
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
# ★ 随机起始格只能取 8~15：第 22 步会用同一个账号约「18 点起 2 小时」，
#   取到 17~20 就会与它时段重叠 → 22.1 变成 4092。这个坑以前有约三成概率出现，
#   只是每次都被当成"脚本偶发"，其实是两步共用了同一个测试账号。
$smokeStart = Get-Random -Minimum 8 -Maximum 16
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

# =====================================================================
# 下面是 V2 步骤（S0~S7）：规则中心、开放日历、分区/桌位、预约闸门、
# 暂离、信用与申诉、候补、通知、巡查、大屏、台账导出、审计。
# 每步都带期望值；跑完给出 PASS/FAIL/SKIP 计数与非零退出码。
# =====================================================================

# ---------- 17. V2 权限矩阵：四角色 × 三命名空间 ----------
$matrix = @{
    stuApi   = Biz-Code (Invoke-Api GET '/api/floors' $null $stuToken)
    teaApi   = Biz-Code (Invoke-Api GET '/api/floors' $null $tch.data.token)
    libApi   = Biz-Code (Invoke-Api GET '/api/floors' $null $lib.data.token)
    admApi   = Biz-Code (Invoke-Api GET '/api/floors' $null $admToken)
    stuStaff = Biz-Code (Invoke-Api GET '/api/staff/patrol' $null $stuToken)
    teaStaff = Biz-Code (Invoke-Api GET '/api/staff/patrol' $null $tch.data.token)
    libStaff = Biz-Code (Invoke-Api GET '/api/staff/patrol' $null $lib.data.token)
    admStaff = Biz-Code (Invoke-Api GET '/api/staff/patrol' $null $admToken)
    stuAdmin = Biz-Code (Invoke-Api GET '/api/admin/rules' $null $stuToken)
    teaAdmin = Biz-Code (Invoke-Api GET '/api/admin/rules' $null $tch.data.token)
    libAdmin = Biz-Code (Invoke-Api GET '/api/admin/rules' $null $lib.data.token)
    admAdmin = Biz-Code (Invoke-Api GET '/api/admin/rules' $null $admToken)
}
Show '17. V2 权限矩阵（四角色 × 三个命名空间）' $matrix
Expect '17.1 学生读 /api/floors'            $matrix.stuApi   0
Expect '17.2 教师读 /api/floors'            $matrix.teaApi   0
Expect '17.3 馆员读 /api/floors'            $matrix.libApi   0
Expect '17.4 管理员读 /api/floors'          $matrix.admApi   0
Expect '17.5 学生读馆员面被拒'              $matrix.stuStaff 4030
Expect '17.6 教师读馆员面被拒'              $matrix.teaStaff 4030
Expect '17.7 馆员读馆员面放行'              $matrix.libStaff 0
Expect '17.8 管理员读馆员面放行'            $matrix.admStaff 0
Expect '17.9 学生读管理面被拒'              $matrix.stuAdmin 4030
Expect '17.10 馆员读管理面被拒'             $matrix.libAdmin 4030
Expect '17.11 管理员读管理面放行'           $matrix.admAdmin 0

# ---------- 18. 规则中心：读 / 改 / 历史 / 复原 ----------
$rulesBefore = Invoke-Api GET '/api/admin/rules?rgroup=BOOK' $null $admToken
Expect '18.1 规则列表（BOOK 组）' $rulesBefore.code 0
$bookRule = $rulesBefore.data | Where-Object { $_.rkey -eq 'book.check-in-before-min' } | Select-Object -First 1
Expect '18.2 BOOK 组至少 4 条规则' ($rulesBefore.data.Count -ge 4) $true
$origRule = $bookRule.rvalue
$null = Invoke-Api PUT '/api/admin/rules' @(@{ rkey = 'book.check-in-before-min'; value = '21' }) $admToken
$afterRule = (Invoke-Api GET '/api/admin/rules?rgroup=BOOK' $null $admToken).data |
    Where-Object { $_.rkey -eq 'book.check-in-before-min' } | Select-Object -First 1
Expect '18.3 改值立即生效（免重启）' $afterRule.rvalue 21
$hist = Invoke-Api GET '/api/admin/rules/history?rkey=book.check-in-before-min' $null $admToken
Expect '18.4 变更历史可读' $hist.code 0
Expect '18.5 历史首条 newValue' $hist.data[0].newValue 21
Expect '18.6 历史首条 oldValue 是改前值' $hist.data[0].oldValue $origRule
$null = Invoke-Api PUT '/api/admin/rules' @(@{ rkey = 'book.check-in-before-min'; value = $origRule }) $admToken
$restored = (Invoke-Api GET '/api/admin/rules?rgroup=BOOK' $null $admToken).data |
    Where-Object { $_.rkey -eq 'book.check-in-before-min' } | Select-Object -First 1
Expect '18.7 规则已复原（不留冒烟痕迹）' $restored.rvalue $origRule
Show '18x. 学生面拿到的规则（同一出处，不是第二套常量）' (Invoke-Api GET '/api/config/booking' $null $stuToken).data

# ---------- 19. 账本一致性：sum(delta) == credit_score ----------
$consistency = Invoke-Api GET '/api/admin/rules/consistency' $null $admToken
Show '19. 信用账本对账（应为空列表）' $consistency
Expect '19.1 对账接口可达' $consistency.code 0
Expect '19.2 无绕过唯一写入口的改动' $consistency.data.Count 0

# ---------- 20. 开放日历与日型 ----------
$profiles = Invoke-Api GET '/api/admin/open/profiles' $null $admToken
Expect '20.1 开放方案列表' $profiles.code 0
Expect '20.2 至少配置了工作日/周末/考试周' ($profiles.data.Count -ge 3) $true
$tomorrow2 = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
$dayRule = Invoke-Api GET "/api/admin/open/day-rule?date=$tomorrow2" $null $admToken
Show '20. 明日日型规则' $dayRule.data
Expect '20.3 明日日型可解析' $dayRule.code 0
Expect '20.4 当日时段数为正' ($dayRule.data.slotCount -gt 0) $true

# ---------- 21. 分区与桌位（馆员命名空间） ----------
Expect '21.1 馆员读分区' (Invoke-Api GET "/api/staff/zones?floorId=$floorId" $null $lib.data.token).code 0
Expect '21.2 学生读分区被拒（/api/staff/**）' (Biz-Code (Invoke-Api GET "/api/staff/zones?floorId=$floorId" $null $stuToken)) 4030
$zones = (Invoke-Api GET "/api/zones?floorId=$floorId" $null $stuToken).data
$groupZone = $zones | Where-Object { $_.groupRequired -eq $true } | Select-Object -First 1
$noLeaveZone = $zones | Where-Object { $_.leaveAllowed -eq $false } | Select-Object -First 1
$plainZone = $zones | Where-Object { $_.groupRequired -ne $true } | Select-Object -First 1
$zoneMap = Invoke-Api GET "/api/seats/map?floorId=$floorId&date=$tomorrow2&slot=18" $null $stuToken
$plainSeat = $zoneMap.data.seats | Where-Object { $_.zoneId -eq $plainZone.zoneId } | Select-Object -First 1
$groupSeat = $zoneMap.data.seats | Where-Object { $_.zoneId -eq $groupZone.zoneId } | Select-Object -First 1
Show '21. 分区（V2 起分区是规则载体，按 flag 取而不是按名字匹配）' @{
    plainZoneId = $plainZone.zoneId; plainSeatNo = $plainSeat.seatNo
    groupZoneId = $groupZone.zoneId; groupSeatNo = $groupSeat.seatNo
    noLeaveZoneId = $noLeaveZone.zoneId
}
Expect '21.3 座位已归属分区' ($plainSeat.zoneId -gt 0) $true
Expect '21.4 存在「仅组队」分区' ($null -ne $groupZone) $true
Expect '21.5 存在「不可暂离」分区' ($null -ne $noLeaveZone) $true

# ---------- 22. V2 预约闸门矩阵（明天，与当前时刻无关） ----------
$smoke2 = (Invoke-Api POST '/api/auth/login' @{ username = "smoke$stamp"; password = '123456' }).data.token
$freeTomorrow = $zoneMap.data.seats |
    Where-Object { $_.displayStatus -eq 'FREE' -and $_.zoneId -eq $plainZone.zoneId } | Select-Object -First 1
$made = Invoke-Api POST '/api/reservations' @{
    seatId = $freeTomorrow.seatId; date = $tomorrow2; startSlot = 18; duration = 2
} $smoke2
Show '22. 预约（静音区 2 小时）' $made
Expect '22.1 预约成功' (Biz-Code $made) 0
$dup = Invoke-Api POST '/api/reservations' @{
    seatId = $freeTomorrow.seatId; date = $tomorrow2; startSlot = 19; duration = 1
} $smoke2
Expect '22.2 同一时段不可重复预约（4092）' (Biz-Code $dup) 4092
$seminarFree = $zoneMap.data.seats |
    Where-Object { $_.displayStatus -eq 'FREE' -and $_.zoneId -eq $groupZone.zoneId } | Select-Object -First 1
$groupOnly = Invoke-Api POST '/api/reservations' @{
    seatId = $seminarFree.seatId; date = $tomorrow2; startSlot = 22; duration = 1
} $smoke2
Expect '22.3 仅组队分区不支持个人散座下单（4102）' (Biz-Code $groupOnly) 4102
$leaveFirst = Invoke-Api POST '/api/leaves' @{ reservationId = $made.data.reservationId; reasonCode = 'MEAL' } $smoke2
Expect '22.4 未签到不可登记暂离（4093）' (Biz-Code $leaveFirst) 4093
$cancel2 = Invoke-Api POST "/api/reservations/$($made.data.reservationId)/cancel" $null $smoke2
Expect '22.5 取消成功' (Biz-Code $cancel2) 0

# ---------- 23. 签到 → 暂离 → 返回 → 结束（当前时段链路，闭馆后按 SKIP 处理） ----------
$slots = (Invoke-Api GET '/api/config/slots' $null $stuToken).data
$openNow = $slots | Where-Object { $_.bookable -eq $true } | Select-Object -First 1
# ★ 时长要按「本格到闭馆还剩几格」算：写死 2 小时在傍晚必然 4001（超出开放窗口），
#   于是这条链路只在白天可验 —— 脚本因此只能在固定时刻跑，等于没有覆盖。
$lastSlot = ($slots | Measure-Object -Property slotIndex -Maximum).Maximum
$maxHours = [int][math]::Floor(($lastSlot + 1 - $openNow.slotIndex) / 2)
# ★ 签到窗口 = 开始前 15 分钟 ~ 开始后 15 分钟（PRD §12-9），而 bookable 只给未开始的格子，
#   所以只有距下一格开始不足 15 分钟时这一步才走得通。其余时刻如实 SKIP：
#   把断言改成「签到应失败」测的是时钟，不是规则。
$waitMin = [int]($openNow.slotIndex * 30 - [datetime]::Now.TimeOfDay.TotalMinutes)
if ($null -eq $openNow -or $maxHours -lt 1) {
    Skip '23 签到/暂离链路' ("当前时刻之后已不足一小时可约（首个可约格={0}，窗口末格={1}），交由用例覆盖" `
        -f $(if ($openNow) { $openNow.slotIndex } else { '无' }), $lastSlot)
} elseif ($waitMin -gt 15) {
    Skip '23 签到/暂离链路' ("距首个可约时段还有 {0} 分钟，还没进入签到窗口（开始前 15 分钟），交由用例覆盖" -f $waitMin)
} else {
    $today2 = (Get-Date).ToString('yyyy-MM-dd')
    $todayMap = Invoke-Api GET "/api/seats/map?floorId=$floorId&date=$today2&slot=$($openNow.slotIndex)" $null $stuToken
    $nowSeat = $todayMap.data.seats |
        Where-Object { $_.displayStatus -eq 'FREE' -and $_.zoneId -eq $plainZone.zoneId } | Select-Object -First 1
    $flow = Invoke-Api POST '/api/reservations' @{
        seatId = $nowSeat.seatId; date = $today2; startSlot = $openNow.slotIndex; duration = $maxHours
    } $smoke2
    Show '23. 当前时段链路预约结果' $flow
    if ((Biz-Code $flow) -eq 0) {
        $rid = $flow.data.reservationId
        $ci = Invoke-Api POST "/api/reservations/$rid/check-in" $null $smoke2
        Expect '23.1 签到成功' (Biz-Code $ci) 0
        $lv = Invoke-Api POST '/api/leaves' @{ reservationId = $rid; reasonCode = 'MEAL' } $smoke2
        Expect '23.2 登记暂离成功' (Biz-Code $lv) 0
        Expect '23.3 暂离给出应归时刻（图上要能写「预计 14:35 返回」）' (-not [string]::IsNullOrEmpty($lv.data.plannedEndTime)) $true
        $lv2 = Invoke-Api POST '/api/leaves' @{ reservationId = $rid; reasonCode = 'PHONE' } $smoke2
        Expect '23.4 已在暂离中不可重复登记（4097）' (Biz-Code $lv2) 4097
        $back = Invoke-Api POST "/api/leaves/$($lv.data.leaveId)/return" $null $smoke2
        Expect '23.5 我已返回' (Biz-Code $back) 0
        $fin = Invoke-Api POST "/api/reservations/$rid/finish" $null $smoke2
        Expect '23.6 结束使用' (Biz-Code $fin) 0
    } else {
        Skip '23 签到/暂离链路' "预约未通过：code=$(Biz-Code $flow)"
    }
}

# ---------- 24. 信用、违规与申诉入口 ----------
$credit = Invoke-Api GET '/api/me/credit' $null $stuToken
Show '24. 我的信用' $credit.data.creditScore
Expect '24.1 信用分可读' $credit.code 0
Expect '24.2 分数在 0~100 区间' (($credit.data.creditScore -ge 0) -and ($credit.data.creditScore -le 100)) $true
$myViolations = Invoke-Api GET '/api/me/violations' $null $stuToken
Expect '24.3 我的违规可读' $myViolations.code 0
# 任何不可申诉的违规都必须给原因（PRD：禁止只置灰不给理由）
$blocked = @($myViolations.data | Where-Object { $_.canAppeal -ne $true })
Expect '24.4 不可申诉的违规都带原因' (@($blocked | Where-Object { -not $_.appealDisabledReason }).Count) 0
Expect '24.5 馆员违规台账可读' (Invoke-Api GET '/api/staff/violations' $null $lib.data.token).code 0
Expect '24.6 申诉队列可读' (Invoke-Api GET '/api/staff/appeals?status=PENDING' $null $lib.data.token).code 0

# ---------- 25. 候补队列 ----------
$wl = Invoke-Api GET '/api/waitlist/my' $null $stuToken
Expect '25.1 我的候补可读' $wl.code 0
Expect '25.2 候补不暴露他人身份' (-not ($wl.data | ConvertTo-Json -Depth 6 | Select-String -Pattern 'realName' -Quiet)) $true

# ---------- 26. 通知中心 ----------
$notices = Invoke-Api GET '/api/me/notices' $null $stuToken
Show '26. 通知（未读/总数）' @{ unread = $notices.data.unread; total = $notices.data.total }
Expect '26.1 通知可读' $notices.code 0
Expect '26.2 每条通知带落地路径' (@($notices.data.list | Where-Object { -not $_.link }).Count) 0
# 通知没有写入口：开放写入等于任何人都能给别人发消息。
# ★ 方法不匹配目前回 500 而不是 405，所以这里只能断言「一定不是成功」；
#   等 HTTP 层错误映射（已登记的遗留）修好后，这一行应收紧成期望 405。
ExpectNotZero '26.3 通知没有写入口' (Biz-Code (Invoke-Api POST '/api/me/notices' @{ type = 'VIOLATION' } $stuToken))

# ---------- 27. 巡查看板（机器只登记疑点） ----------
$patrol = Invoke-Api GET '/api/staff/patrol' $null $lib.data.token
Expect '27.1 看板可读' $patrol.code 0
Expect '27.2 学生读看板被拒' (Biz-Code (Invoke-Api GET '/api/staff/patrol' $null $stuToken)) 4030
$acts = Invoke-Api GET '/api/staff/patrol/actions' $null $lib.data.token
Expect '27.3 动作字典四条' $acts.data.Count 4
Expect '27.4 RELEASE 的落库状态是 RELEASED' (($acts.data | Where-Object { $_.action -eq 'RELEASE' }).targetStatus) 'RELEASED'
$noNote = Invoke-Api POST '/api/staff/patrol' @{ seatId = $freeTomorrow.seatId; note = '   ' } $lib.data.token
Expect '27.5 人工登记必须写明现场情况（4001）' (Biz-Code $noNote) 4001

# ---------- 28. 监控大屏：单接口、口径自洽、耗时 ----------
$watch = Measure-Command { $script:dash = Invoke-Api GET '/api/staff/dashboard' $null $lib.data.token }
$sum = 0
foreach ($f in $dash.data.floors) { $sum += $f.totalSeats }
Show '28. 大屏（耗时 ms / 全馆座位 / 楼层加总 / 异常数）' @{
    ms = [int]$watch.TotalMilliseconds
    totalSeats = $dash.data.overview.totalSeats
    floorSum = $sum
    patrolOpen = $dash.data.anomalies.patrolOpen
    onLeave = $dash.data.onLeave
}
Expect '28.1 大屏可读' $dash.code 0
Expect '28.2 楼层加总等于全馆座位（只有一处派生口径）' $sum $dash.data.overview.totalSeats
Expect '28.3 单接口耗时 < 1000ms' ($watch.TotalMilliseconds -lt 1000) $true

# ---------- 29. 台账导出（UTF-8 BOM + 审计） ----------
$auditBefore = (Invoke-Api GET '/api/admin/audit?action=DATA_EXPORT&page=1&size=1' $null $admToken).data.total
$csv = Invoke-WebRequest -Uri "$Base/api/admin/export/patrol" -Headers @{ Authorization = "Bearer $admToken" } -UseBasicParsing
$head = $csv.RawContentStream.ToArray()
Show '29. 巡查台账导出' @{
    status = $csv.StatusCode
    type = $csv.Headers['Content-Type']
    disposition = $csv.Headers['Content-Disposition']
    bom = (@($head[0], $head[1], $head[2]) -join ',')
    bytes = $head.Length
}
Expect '29.1 导出 200' $csv.StatusCode 200
Expect '29.2 Content-Type 是 text/csv' ($csv.Headers['Content-Type'] -match 'text/csv') $true
Expect '29.3 前三个字节是 UTF-8 BOM（Excel 不乱码）' "$($head[0]),$($head[1]),$($head[2])" '239,187,191'
$auditAfter = (Invoke-Api GET '/api/admin/audit?action=DATA_EXPORT&page=1&size=1' $null $admToken).data.total
Expect '29.4 每次导出留一条审计' ($auditAfter - $auditBefore) 1
Expect '29.5 馆员不能拿台账' (Biz-Code (Invoke-Api GET '/api/admin/export/patrol' $null $lib.data.token)) 4030
Expect '29.6 未知类型给出可选项（4001）' (Biz-Code (Invoke-Api GET '/api/admin/export/whatever' $null $admToken)) 4001

# ---------- 30. V2 深链（刷新不白屏） ----------
foreach ($path in @('/m/home', '/m/seat', '/staff/dashboard', '/admin/rules/history', '/admin/export')) {
    $deep = try { (Invoke-WebRequest -Uri "$Base$path" -UseBasicParsing -Method Head).StatusCode } catch { $_.Exception.Response.StatusCode.value__ }
    Expect "30. 深链 $path 可刷新" $deep 200
}

# ---------- 31. 强制释放必填原因并通知学生（PRD §12-26 / 收口偏差 C1） ----------
# ★ 正文比对一律用 ASCII：PowerShell 5.1 按本地代码页解 UTF-8 响应体，
#   中文进了 content 就成了乱码，拿中文字面量去 Contains 只会得到一个假失败。
$toRelease = $null
foreach ($trySlot in @(38, 36, 34, 32)) {
    $relMap = Invoke-Api GET "/api/seats/map?floorId=$floorId&date=$tomorrow2&slot=$trySlot" $null $stuToken
    $relSeat = $relMap.data.seats |
        Where-Object { $_.displayStatus -eq 'FREE' -and $_.zoneId -eq $plainZone.zoneId } | Select-Object -First 1
    if ($null -eq $relSeat) { continue }
    $toRelease = Invoke-Api POST '/api/reservations' @{
        seatId = $relSeat.seatId; date = $tomorrow2; startSlot = $trySlot; duration = 1
    } $smoke2
    if ((Biz-Code $toRelease) -eq 0) { break }
}
if ($null -ne $toRelease -and (Biz-Code $toRelease) -eq 0) {
    $relId = $toRelease.data.reservationId
    Expect '31.1 空原因必须被拒（4001）' (Biz-Code (
        Invoke-Api POST "/api/admin/reservations/$relId/release" @{ reason = '  ' } $admToken)) 4001
    Expect '31.2 强制释放成功' (Biz-Code (
        Invoke-Api POST "/api/admin/reservations/$relId/release" @{ reason = 'smoke-device-maintenance' } $admToken)) 0
    $relNotices = Invoke-Api GET '/api/me/notices' $null $smoke2
    $hit = @($relNotices.data.list | Where-Object { $_.type -eq 'SEAT_RELEASED' -and $_.refId -eq $relId })
    Show '31. 释放通知' @{ matched = $hit.Count; hasLink = ($hit[0].link -eq '/my/reservations') }
    Expect '31.3 释放给学生落一条站内通知（不是只有巡查路径才通知）' $hit.Count 1
    Expect '31.4 通知正文带上释放原因' ($hit[0].content.Contains('smoke-device-maintenance')) $true
    Expect '31.5 通知可跳转到我的预约' $hit[0].link '/my/reservations'
} else {
    Skip '31 强制释放通知' "未能约到可释放的预约（时段已被占或闸门拦住）"
}

# ---------- 32. 暂离原因字典带本人计数（PRD §6.2 表 + §13 置灰必给原因） ----------
$dict = Invoke-Api GET '/api/leaves/reasons' $null $smoke2
$dMeal = @($dict.data | Where-Object { $_.code -eq 'MEAL' })[0]
Show '32. 原因字典' @{
    count = $dict.data.Count
    mealDailyLimit = $dMeal.dailyLimit
    mealTodayUsed = $dMeal.todayUsed
    exhaustedFlag = $dMeal.exhausted
}
Expect '32.1 六个原因' $dict.data.Count 6
Expect '32.2 就餐各自每日 2 次（V15 把 PRD §6.2 的值真正落进数据）' $dMeal.dailyLimit 2
Expect '32.3 字典带本人今日已用次数（选择器靠它置灰，不让用户选了才被拒）' ($null -ne $dMeal.todayUsed) $true
Expect '32.4 标了用满的原因必须给文案' (@(
    $dict.data | Where-Object { $_.exhausted -eq $true -and [string]::IsNullOrEmpty($_.exhaustedReason) }).Count) 0

# ---------- 33. 组队人数上限（PRD §6.3「2~6 人一组」；桌子多大不决定队伍上限） ----------
$cfg = Invoke-Api GET '/api/config/booking' $null $stuToken
Expect '33.1 配置里给出一组人数上限（前端选项按它生成）' $cfg.data.groupMaxMembers 6
Expect '33.2 成团下限也在配置里' $cfg.data.groupMinMembers 2
$grpTables = Invoke-Api GET "/api/staff/tables?zoneId=$($groupZone.zoneId)" $null $lib.data.token
$grpTableId = ($grpTables.data | Select-Object -First 1).tableId
if ($null -eq $grpTableId) {
    Skip '33.3 超限建队被拒' '仅组队分区下没有建模桌位'
} else {
    Expect '33.3 超过上限的建队被拒（4001）' (Biz-Code (Invoke-Api POST '/api/groups' @{
        zoneId = $groupZone.zoneId; tableId = $grpTableId; date = $tomorrow2; startSlot = 18; duration = 2
        maxMembers = ($cfg.data.groupMaxMembers + 3)
    } $smoke2)) 4001
    # 省略上限时夹到 min(座位数, 6)，所以「成团 7 人」也不能被放过
    Expect '33.4 成团人数超过夹后上限同样被拒（两条上下限同源）' (Biz-Code (Invoke-Api POST '/api/groups' @{
        zoneId = $groupZone.zoneId; tableId = $grpTableId; date = $tomorrow2; startSlot = 18; duration = 2
        minMembers = ($cfg.data.groupMaxMembers + 1)
    } $smoke2)) 4001
}

# ---------- 34. 可提前天数取「本区口径」（V2 起分区值取代全局默认，全局只在分区没配时兜底） ----------
$tightZone = @($zones | Where-Object { $_.advanceDays -eq 1 })[0]
if ($null -eq $tightZone) {
    Skip '34 分区窗口' '本楼层没有「提前 1 天」的分区，无法演示本区口径比全馆更紧'
} else {
    $tightMap = Invoke-Api GET "/api/seats/map?floorId=$floorId&date=$tomorrow2&slot=20" $null $stuToken
    $tightSeat = $tightMap.data.seats |
        Where-Object { $_.displayStatus -eq 'FREE' -and $_.zoneId -eq $tightZone.zoneId } | Select-Object -First 1
    if ($null -eq $tightSeat) {
        Skip '34 分区窗口' "$($tightZone.name) 在 $tomorrow2 20:00 无空座"
    } else {
        $beyond = Invoke-Api POST '/api/reservations' @{
            seatId = $tightSeat.seatId; date = $tomorrow2; startSlot = 20; duration = 1
        } $smoke2
        $beyondMsg = Biz-Message $beyond
        Show '34. 超本区窗口的预约' @{ code = (Biz-Code $beyond); msgLen = $beyondMsg.Length }
        Expect '34.1 超出本区可提前天数应 4001' (Biz-Code $beyond) 4001
        Expect '34.2 报错必须给出天数而不是空话' ($beyondMsg.Length -gt 8) $true
    }
}

# ---------- 35. 单日累计上限是全馆口径（PRD §12-3：换分区也一样计入） ----------
# 关键设计：前两段刻意落在**两个不同分区**，这样第三段被拦时不可能是分区级上限干的，
# 才能真正证明"全馆累计"这道闸门存在（两段都挤在 1F 静音区的话，测的是分区口径）。
$capUser = "cap$stamp"
Invoke-Api POST '/api/auth/register' @{
    username = $capUser; password = '123456'; confirmPassword = '123456'; realName = 'cap-probe'
} $null | Out-Null
$capToken = (Invoke-Api POST '/api/auth/login' @{ username = $capUser; password = '123456' }).data.token
$floor3 = (Invoke-Api GET '/api/floors' $null $capToken).data[2].floorId
$zone3 = @((Invoke-Api GET "/api/zones?floorId=$floor3" $null $capToken).data |
    Where-Object { $_.code -eq '3F-SILENT' })[0]
$capSeats = @()
$capBooked = 0
$segments = @(
    @{ floor = $floorId; zone = $plainZone.zoneId; slot = 16; hours = 4 },
    @{ floor = $floor3;  zone = $zone3.zoneId;     slot = 24; hours = 2 })
foreach ($seg in $segments) {
    $segSlots = [int]($seg.hours * 2)
    $seatId = Free-Seat -Floor $seg.floor -Date $tomorrow2 -Slot $seg.slot -Count $segSlots `
        -Zone $seg.zone -Token $capToken
    if ($null -eq $seatId) { continue }
    $r = Invoke-Api POST '/api/reservations' @{
        seatId = $seatId; date = $tomorrow2; startSlot = $seg.slot; duration = $seg.hours
    } $capToken
    if ((Biz-Code $r) -eq 0) { $capBooked++; $capSeats += $r.data.reservationId }
}
Expect '35.1 前置：跨两个分区约满 6 小时（4+2）' $capBooked 2
$thirdSeat = Free-Seat -Floor $floorId -Date $tomorrow2 -Slot 30 -Count 2 -Zone $plainZone.zoneId -Token $capToken
$capThird = Invoke-Api POST '/api/reservations' @{
    seatId = $thirdSeat; date = $tomorrow2; startSlot = 30; duration = 1
} $capToken
Show '35. 全馆单日上限' @{ code = (Biz-Code $capThird); msgLen = (Biz-Message $capThird).Length }
Expect '35.2 第 7 小时必须被拦（4098，本区累计仅 300 分钟，只可能是全馆口径）' (Biz-Code $capThird) 4098
Expect '35.3 报错要给出上限数字' ((Biz-Message $capThird).Length -gt 8) $true
foreach ($id in $capSeats) { Invoke-Api POST "/api/reservations/$id/cancel" $null $capToken | Out-Null }

Write-Host "========================================" -ForegroundColor Green
Write-Host ("冒烟脚本执行完毕：PASS {0} / FAIL {1} / SKIP {2}" -f $script:passCount, $script:failCount, $script:warnCount) `
    -ForegroundColor $(if ($script:failCount -gt 0) { 'Red' } else { 'Green' })
if ($script:failCount -gt 0) { exit 1 }
exit 0

