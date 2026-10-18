#!/usr/bin/env bash
# 身份域隔离与会话吊销的负向 E2E（spec application/identity-realm §7、评审 R01–R05/R09）。
#
# 真实二进制 + 真实 PostgreSQL + 隔离的 Redis。管理员与终端用户刻意用同一个 id（1），
# 复现评审里取得 *:*:* 的条件，然后逐项断言「不该通过的都不通过」。
#
# 用法：tests/security/realm-session-e2e.sh
#   APP_BIN   应用二进制，默认 application/build/bin/macosArm64/debugExecutable/application.kexe
#   PGHOST/PGPORT/PGUSER/PGPASSWORD  PostgreSQL 连接（默认本机）
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
APP_BIN="${APP_BIN:-$ROOT/application/build/bin/macosArm64/debugExecutable/application.kexe}"
PGUSER="${PGUSER:-$(whoami)}"; PGHOST="${PGHOST:-localhost}"; PGPORT="${PGPORT:-5432}"
export PGUSER PGHOST PGPORT
DB="realm_e2e_$$"
PORT="${E2E_PORT:-18090}"
REDIS_PORT="${E2E_REDIS_PORT:-6398}"
U="http://127.0.0.1:$PORT"
CT="Content-Type: application/json"
WORK="$(mktemp -d)"
PASS=0; FAIL=0; APP_PID=""; REDIS_PID=""

cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null && wait "$APP_PID" 2>/dev/null
  [ -n "$REDIS_PID" ] && kill "$REDIS_PID" 2>/dev/null && wait "$REDIS_PID" 2>/dev/null
  dropdb --if-exists --force "$DB" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM
pass() { echo "  ✅ $1"; PASS=$((PASS+1)); }
fail() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
expect() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (got $2, want $3)"; fi; }
q() { psql -d "$DB" -tAc "$1"; }
rc() { redis-cli -p "$REDIS_PORT" "$@"; }
json() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1" 2>/dev/null; }
# status <method> <path> [token] [body] → HTTP 状态码
status() {
  curl -s -o /dev/null -w "%{http_code}" -X "$1" "$U$2" -H "$CT" ${3:+-H "Authorization: Bearer $3"} ${4:+-d "$4"}
}
# envelope <method> <path> [token] [body] → 响应体
envelope() {
  curl -s -X "$1" "$U$2" -H "$CT" ${3:+-H "Authorization: Bearer $3"} ${4:+-d "$4"}
}

export NETON_SECURITY__JWT__SECRETKEY="realm-e2e-only-jwt-secret-0123456789abcdef"
export NETON_SECURITY__VERIFICATION__SIMULATE=true
export NETON_SECURITY__VERIFICATION__RESEND_COOLDOWN_SECONDS=0
export NETON_REDIS__PORT="$REDIS_PORT"

# ── 环境 ──
redis-server --port "$REDIS_PORT" --save "" --appendonly no >"$WORK/redis.log" 2>&1 & REDIS_PID=$!
createdb "$DB" || { echo "createdb failed"; exit 1; }
cp -R "$ROOT/application/config" "$WORK/config"
sed -i.bak "s#postgresql://[^\"]*#postgresql://$PGUSER:${PGPASSWORD:-}@$PGHOST:$PGPORT/$DB#" "$WORK/config/database.conf" "$WORK/config/application.conf"
sed -i.bak "s/^port = .*/port = $PORT/" "$WORK/config/application.conf"
rm -f "$WORK/config/"*.bak

for _ in $(seq 1 30); do rc ping >/dev/null 2>&1 && break; sleep 0.2; done
( cd "$WORK" && "$APP_BIN" migrate up >"$WORK/migrate.log" 2>&1 ) || { echo "migrate failed"; tail -5 "$WORK/migrate.log"; exit 1; }
echo "[0] 占位密钥必须拒绝启动"
( cd "$WORK" && NETON_SECURITY__JWT__SECRETKEY="REPLACE_ME_SET_NETON_SECURITY__JWT__SECRETKEY" exec "$APP_BIN" >"$WORK/placeholder.log" 2>&1 ) & ph=$!
for _ in $(seq 1 40); do kill -0 "$ph" 2>/dev/null || break; sleep 0.5; done
if kill -0 "$ph" 2>/dev/null; then
  kill "$ph"; wait "$ph" 2>/dev/null; fail "占位密钥：进程仍在运行"
else
  grep -q "JWT secret is still the placeholder" "$WORK/placeholder.log" && pass "占位密钥启动失败并给出原因" || fail "占位密钥：退出但原因不对 $(tail -2 "$WORK/placeholder.log")"
fi

( cd "$WORK" && exec "$APP_BIN" >"$WORK/app.log" 2>&1 ) & APP_PID=$!
for _ in $(seq 1 60); do curl -s -o /dev/null "$U/admin/system/site/public" && break; sleep 0.5; done

# ── 登录：管理员 id=1；账号注册后把 id 改成 1，再重新登录 ──
admin_login() {
  envelope POST /admin/system/auth/login "" "{\"username\":\"$1\",\"password\":\"$2\"}"
}
account_login() {
  local mobile=$1
  envelope POST /app/auth/send-sms-code "" "{\"mobile\":\"$mobile\",\"scene\":1}" >/dev/null
  local code; code=$(rc get "neton:vc:account:login:sms:$mobile")
  envelope POST /app/auth/sms-login "" "{\"mobile\":\"$mobile\",\"smsCode\":\"$code\",\"device\":{\"deviceId\":\"e2e-$mobile\",\"platform\":\"ios\"}}"
}
MOBILE="+8613900099001"
account_login "$MOBILE" >/dev/null
q "UPDATE account_users SET id = 1 WHERE mobile = '$MOBILE'" >/dev/null
UL=$(account_login "$MOBILE"); U_ACC=$(echo "$UL" | json "['data']['accessToken']"); U_REF=$(echo "$UL" | json "['data']['refreshToken']")
AL=$(admin_login admin admin123); A_ACC=$(echo "$AL" | json "['data']['accessToken']"); A_REF=$(echo "$AL" | json "['data']['refreshToken']")
[ -n "$U_ACC" ] && [ -n "$A_ACC" ] || { echo "login failed: admin=$AL account=$UL"; exit 1; }
expect "账号 id 与管理员 id 相同（复现条件）" "$(echo "$UL" | json "['data']['userId']")" "1"

m1=$(admin_login nobody_here wrongPassw0rd | json "['message']"); m2=$(admin_login admin wrongPassw0rd | json "['message']")
expect "管理员登录：不存在的用户与密码错误提示一致" "$m1" "$m2"

echo "[1] 身份域交叉矩阵"
expect "管理员 access → /admin 通过" "$(status GET /admin/system/user/profile/get "$A_ACC")" "200"
expect "账号 access → /app 通过" "$(status GET /app/member/user/get "$U_ACC")" "200"
expect "账号 access → /admin 拒绝" "$(status GET /admin/system/user/profile/get "$U_ACC")" "401"
expect "管理员 access → /app 拒绝" "$(status GET /app/member/user/get "$A_ACC")" "401"
expect "账号 refresh 当 access 用 → /app 拒绝" "$(status GET /app/member/user/get "$U_REF")" "401"
expect "管理员 refresh 当 access 用 → /admin 拒绝" "$(status GET /admin/system/user/profile/get "$A_REF")" "401"
r=$(envelope POST /admin/system/auth/refresh-token "" "{\"refreshToken\":\"$U_REF\"}")
expect "账号 refresh 换管理员令牌被拒" "$(echo "$r" | json "['data']['accessToken']")" ""
r=$(envelope POST /app/auth/refresh-token "" "{\"refreshToken\":\"$A_REF\",\"deviceId\":\"x\"}")
expect "管理员 refresh 换账号令牌被拒" "$(echo "$r" | json "['data']['accessToken']")" ""

echo "[2] CSRF：凭 Cookie 的写请求必须带 X-Neton-Csrf"
c=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$U/admin/system/user/profile/update" -H "$CT" -H "Cookie: neton_session=$A_ACC" -d '{"nickname":"e2e"}')
expect "Cookie 写请求缺头 → 拒绝" "$c" "401"
c=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$U/admin/system/user/profile/update" -H "$CT" -H "Cookie: neton_session=$A_ACC" -H "X-Neton-Csrf: 1" -d '{"nickname":"e2e"}')
expect "Cookie 写请求带头 → 通过" "$c" "200"
c=$(curl -s -o /dev/null -w "%{http_code}" "$U/admin/system/user/profile/get" -H "Cookie: neton_session=$A_ACC")
expect "Cookie 读请求不需要头" "$c" "200"

echo "[3] 刷新令牌单次使用"
r1=$(envelope POST /admin/system/auth/refresh-token "" "{\"refreshToken\":\"$A_REF\"}"); N_ACC=$(echo "$r1" | json "['data']['accessToken']")
r2=$(envelope POST /admin/system/auth/refresh-token "" "{\"refreshToken\":\"$A_REF\"}")
[ -n "$N_ACC" ] && pass "首次刷新成功" || fail "首次刷新失败: $r1"
[ -n "$(echo "$r2" | json "['data']['accessToken']")" ] && pass "并发窗口内重复刷新放行（多页签同时过期）" || fail "并发刷新被拒: $r2"
q "SELECT 1" >/dev/null
JTI_KEY=$(rc --scan --pattern 'neton:auth:refresh:used:admin:*' | head -1)
rc set "$JTI_KEY" "$(( $(date +%s) - 120 ))" >/dev/null   # 把首次使用时间挪到窗口外，模拟被盗用后重放
r3=$(envelope POST /admin/system/auth/refresh-token "" "{\"refreshToken\":\"$A_REF\"}")
expect "窗口外重复使用 → 拒绝" "$(echo "$r3" | json "['data']['accessToken']")" ""
expect "重放后整个会话作废（刚换出的 access 也失效）" "$(status GET /admin/system/user/profile/get "$N_ACC")" "401"

echo "[4] 退出只吊销当前会话"
S1=$(admin_login admin admin123 | json "['data']['accessToken']")
S2=$(admin_login admin admin123 | json "['data']['accessToken']")
expect "退出接口" "$(status POST /admin/system/auth/logout "$S1")" "200"
expect "退出后该会话 access 失效" "$(status GET /admin/system/user/profile/get "$S1")" "401"
expect "另一个会话不受影响" "$(status GET /admin/system/user/profile/get "$S2")" "200"
expect "账号退出" "$(status POST /app/auth/logout "$U_ACC")" "200"
expect "账号退出后 access 失效" "$(status GET /app/member/user/get "$U_ACC")" "401"
r=$(envelope POST /app/auth/refresh-token "" "{\"refreshToken\":\"$U_REF\",\"deviceId\":\"e2e-$MOBILE\"}")
expect "账号退出后 refresh 失效" "$(echo "$r" | json "['data']['accessToken']")" ""

echo "[5] 改密作废全部会话"
expect "管理员改密" "$(status PUT /admin/system/user/profile/update-password "$S2" '{"oldPassword":"admin123","newPassword":"admin12345"}')" "200"
expect "改密后旧会话失效" "$(status GET /admin/system/user/profile/get "$S2")" "401"
ADM=$(admin_login admin admin12345 | json "['data']['accessToken']")
[ -n "$ADM" ] && pass "新密码可登录" || fail "新密码登录失败"

echo "[6] 撤权即时生效（不等令牌过期）"
ROLE=$(envelope POST /admin/system/role/create "$ADM" '{"code":"e2e_viewer","name":"E2E Viewer"}' | json "['data']")
MENU=$(q "SELECT id FROM system_menus WHERE permission = 'system:user:query' ORDER BY id LIMIT 1")
envelope POST /admin/system/permission/assign-role-menu "$ADM" "{\"roleId\":$ROLE,\"menuIds\":[$MENU]}" >/dev/null
OPS=$(envelope POST /admin/system/user/create "$ADM" '{"username":"e2e_ops","password":"opsPassw0rd","nickname":"Ops"}' | json "['data']")
envelope POST /admin/system/permission/assign-user-role "$ADM" "{\"userId\":$OPS,\"roleIds\":[$ROLE]}" >/dev/null
OPS_ACC=$(admin_login e2e_ops opsPassw0rd | json "['data']['accessToken']")
expect "授权后可访问" "$(status GET '/admin/system/user/page?pageNo=1&pageSize=1' "$OPS_ACC")" "200"
OTHER=$(q "SELECT id FROM system_menus WHERE permission = 'system:role:query' ORDER BY id LIMIT 1")
envelope POST /admin/system/permission/assign-role-menu "$ADM" "{\"roleId\":$ROLE,\"menuIds\":[$OTHER]}" >/dev/null
expect "撤权后同一令牌立即 403" "$(status GET '/admin/system/user/page?pageNo=1&pageSize=1' "$OPS_ACC")" "403"
expect "新授予的权限同样立即生效" "$(status GET '/admin/system/role/page?pageNo=1&pageSize=1' "$OPS_ACC")" "200"

echo "[7] 按账号的登录失败冷却（管理员登录入口的 IP 限流此时已被前面的步骤用掉，改测账号入口）"
LOCK_MOBILE="+8613900099002"
m1=$(envelope POST /app/auth/login "" "{\"mobile\":\"$LOCK_MOBILE\",\"password\":\"wrongPassw0rd\"}" | json "['message']")
m2=$(envelope POST /app/auth/login "" "{\"mobile\":\"$MOBILE\",\"password\":\"wrongPassw0rd\"}" | json "['message']")
expect "账号登录：未注册号码与密码错误提示一致" "$m1" "$m2"
for i in 1 2 3 4; do envelope POST /app/auth/login "" "{\"mobile\":\"$LOCK_MOBILE\",\"password\":\"wrongPassw0rd\"}" >/dev/null; done
r=$(envelope POST /app/auth/login "" "{\"mobile\":\"$LOCK_MOBILE\",\"password\":\"wrongPassw0rd\"}")
echo "$r" | grep -q "LOGIN_LOCKED" && pass "连续失败 5 次后锁定" || fail "未锁定: $r"
RA=$(curl -s -D - -o /dev/null -X POST "$U/app/auth/login" -H "$CT" -d "{\"mobile\":\"$LOCK_MOBILE\",\"password\":\"wrongPassw0rd\"}" | tr -d '\r' | awk -F': ' 'tolower($1)=="retry-after"{print $2}')
[ -n "$RA" ] && [ "$RA" -gt 0 ] && pass "冷却响应带 Retry-After（$RA 秒）" || fail "冷却响应缺 Retry-After"
LOCK_KEYS=$(rc --scan --pattern '*auth:fail:account:*' | tr '\n' ' ')
[ -n "$LOCK_KEYS" ] && ! echo "$LOCK_KEYS" | grep -q "13900099002" && pass "冷却键不含原始账号（摘要 + IP）" || fail "冷却键异常: $LOCK_KEYS"

echo "[8] 全端退出与登录审计"
D1=$(account_login "$MOBILE" | json "['data']['accessToken']")
D2=$(account_login "$MOBILE" | json "['data']['accessToken']")
expect "账号全端退出" "$(status POST /app/auth/logout-all "$D1")" "200"
expect "全端退出后其他设备的会话也失效" "$(status GET /app/member/user/get "$D2")" "401"
OK_LOGINS=$(q "SELECT count(*) FROM system_login_logs WHERE username = 'admin' AND login_result = 0")
BAD_LOGINS=$(q "SELECT count(*) FROM system_login_logs WHERE username = 'nobody_here' AND login_result = 1")
[ "${OK_LOGINS:-0}" -gt 0 ] && pass "登录成功写入审计（$OK_LOGINS 条）" || fail "没有成功登录审计"
[ "${BAD_LOGINS:-0}" -gt 0 ] && pass "登录失败写入审计（$BAD_LOGINS 条）" || fail "没有失败登录审计"
A1=$(admin_login admin admin12345 | json "['data']['accessToken']")
A2=$(admin_login admin admin12345 | json "['data']['accessToken']")
expect "管理员全端退出" "$(status POST /admin/system/auth/logout-all "$A1")" "200"
expect "全端退出后其他会话也失效" "$(status GET /admin/system/user/profile/get "$A2")" "401"

echo
echo "═══ 结果：$PASS passed, $FAIL failed ═══"
[ "$FAIL" = "0" ]
