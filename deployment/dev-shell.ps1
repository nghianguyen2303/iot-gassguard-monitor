# ============================================================================
# Lenh tat cho moi truong dev. Nap vao terminal PowerShell bang 1 dong:
#   iex (Get-Content -Raw E:\IOT\iot-gas-monitor\deployment\dev-shell.ps1)
# (Dung iex nen khong bi chan boi Execution Policy cua Windows.)
# File nay KHONG chua mat khau: moi gia tri bi mat doc tu file .env (khong commit).
# ============================================================================
$global:IOT_ROOT = "E:\IOT\iot-gas-monitor"
$global:IOT_ENV  = Join-Path $IOT_ROOT ".env"
$global:IOT_COMPOSE = Join-Path $IOT_ROOT "deployment\docker-compose.yml"

if (-not (Test-Path $IOT_ENV)) { Write-Host "Khong tim thay $IOT_ENV" -ForegroundColor Red; return }

function global:Get-EnvValue([string]$key) {
    $m = Select-String -Path $global:IOT_ENV -Pattern "^$key=(.+)$"
    if ($m) { return $m.Matches[0].Groups[1].Value.Trim() }
    return $null
}

$global:ADMIN_PASS   = Get-EnvValue "MQTT_ADMIN_PASSWORD"
$global:BACKEND_PASS = Get-EnvValue "MQTT_BACKEND_PASSWORD"
$global:ADMIN_PW     = Get-EnvValue "APP_ADMIN_PASSWORD"
$global:PGUSER       = Get-EnvValue "POSTGRES_USER"
$global:PGDB         = Get-EnvValue "POSTGRES_DB"

# docker compose cua project (chay duoc o bat ky thu muc nao)
function global:dc     { docker compose --env-file $global:IOT_ENV -f $global:IOT_COMPOSE @args }
# Quan tri Dynamic Security cua Mosquitto bang tai khoan admin
function global:mqctrl { docker compose --env-file $global:IOT_ENV -f $global:IOT_COMPOSE exec mosquitto mosquitto_ctrl -u admin -P $global:ADMIN_PASS dynsec @args }
function global:mpub   { docker compose --env-file $global:IOT_ENV -f $global:IOT_COMPOSE exec mosquitto mosquitto_pub @args }
function global:msub   { docker compose --env-file $global:IOT_ENV -f $global:IOT_COMPOSE exec mosquitto mosquitto_sub @args }
# Chay cau SQL trong PostgreSQL:  psql "select count(*) from telemetry"
function global:psql([string]$sql) { docker compose --env-file $global:IOT_ENV -f $global:IOT_COMPOSE exec postgres psql -U $global:PGUSER -d $global:PGDB -c $sql }

# Goi REST API cua backend:  Api POST /api/auth/login @{username="admin"; password=$ADMIN_PW}
function global:Api($Method, $Path, $Body = $null, $Token = $null) {
    $h = @{}; if ($Token) { $h.Authorization = "Bearer $Token" }
    $p = @{ Method = $Method; Uri = "http://localhost:8080$Path"; Headers = $h
            ContentType = "application/json; charset=utf-8"; UseBasicParsing = $true }
    if ($Body) { $p.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10)) }
    try {
        $r = Invoke-WebRequest @p; $code = [int]$r.StatusCode
        $txt = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { Write-Host "Khong ket noi duoc backend: $($_.Exception.Message)" -ForegroundColor Red; return }
        $code = [int]$resp.StatusCode
        $txt = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
    }
    Write-Host "[$code] $Method $Path" -ForegroundColor Cyan; Write-Host $txt
    if ($txt) { try { return ($txt | ConvertFrom-Json) } catch {} }
}

# Tao tai khoan MQTT cho 1 thiet bi thu (Buoc 7 backend se tu lam viec nay):  New-TestDevice sim-001 simpass123
function global:New-TestDevice([string]$D, [string]$Password) {
    mqctrl createRole "dev-$D"
    foreach ($t in "telemetry","heartbeat","status","event","cmd/ack","config/ack") { mqctrl addRoleACL "dev-$D" publishClientSend "iot/v1/devices/$D/$t" allow }
    foreach ($t in "cmd","config") { mqctrl addRoleACL "dev-$D" subscribeLiteral "iot/v1/devices/$D/$t" allow }
    mqctrl createClient $D -c $D -p $Password
    mqctrl addClientRole $D "dev-$D"
    mqctrl getRole "dev-$D"
}
function global:Remove-TestDevice([string]$D) {
    mqctrl deleteClient $D
    mqctrl deleteRole "dev-$D"
}

# ---- Buoc 13: chay toan bo he thong bang Docker (profile "app") ----
# app-up: build + chay backend, frontend (va ha tang). Mo https://localhost:8443
function global:app-up   { docker compose --env-file $global:IOT_ENV -f $global:IOT_COMPOSE --profile app up -d --build @args }
# app-down: dung + xoa container backend, frontend (giu mosquitto, postgres va du lieu)
function global:app-down { docker compose --env-file $global:IOT_ENV -f $global:IOT_COMPOSE --profile app rm -s -f backend frontend }
# app-logs [service]: xem log, vd: app-logs backend
function global:app-logs { docker compose --env-file $global:IOT_ENV -f $global:IOT_COMPOSE --profile app logs -f --tail 100 @args }

# ---- TLS: tao CA + chung chi may chu (chay trong Docker, khong can cai OpenSSL) ----
# gen-certs 192.168.1.250 172.20.10.2   (liet ke moi IP LAN cua may chu; chay lai khi doi mang, CA giu nguyen)
function global:gen-certs { docker run --rm -v "$($global:IOT_ROOT):/repo" alpine:3.20 sh /repo/deployment/certs/gen-certs.sh @args }

$ok = ($ADMIN_PASS -and $BACKEND_PASS -and $ADMIN_PW)
if ($ok) { Write-Host "Da nap: dc, mqctrl, mpub, msub, psql, Api, New-TestDevice, Remove-TestDevice, app-up, app-down, app-logs, gen-certs" -ForegroundColor Green }
else     { Write-Host "Thieu bien trong .env (MQTT_ADMIN_PASSWORD / MQTT_BACKEND_PASSWORD / APP_ADMIN_PASSWORD)" -ForegroundColor Yellow }
