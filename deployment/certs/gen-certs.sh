#!/bin/sh
# ============================================================================
# Tạo chứng chỉ TLS cho GasGuard (mục 4.5: kết nối mã hóa).
#
#   CA riêng của nhóm (ca/ca.crt, ca/ca.key)  --ký-->  chứng chỉ máy chủ (server/server.crt, server/server.key)
#   Chứng chỉ máy chủ dùng chung cho Mosquitto (MQTT/TLS :8883) và nginx (HTTPS :8443).
#   Container chỉ được gắn thư mục server/; khóa bí mật của CA (ca/ca.key) không rời khỏi máy.
#
# Chạy từ thư mục gốc repo (PowerShell), không cần cài OpenSSL:
#   docker run --rm -v "${PWD}:/repo" alpine:3.20 sh /repo/deployment/certs/gen-certs.sh 192.168.1.250
# Thêm nhiều địa chỉ (IP nhà, IP trên hotspot, tên máy...) cách nhau bởi dấu cách.
#
# - CA chỉ tạo MỘT lần; chạy lại script chỉ cấp lại chứng chỉ máy chủ cho danh sách địa chỉ mới
#   -> không phải nạp lại CA vào ESP32, không phải cài lại CA vào trình duyệt.
#   Muốn tạo lại cả CA: xóa thư mục ca/ rồi chạy lại (khi đó phải nạp lại ESP32 và cài lại CA vào máy).
# - localhost, 127.0.0.1 và mosquitto (tên container) luôn có sẵn trong chứng chỉ.
# - Kết quả: deployment/certs/ca/, deployment/certs/server/ (KHÔNG commit, đã có trong .gitignore),
#   firmware/gas-node/ca_cert.h (chứng chỉ CA cho ESP32). server/ca.crt là bản sao CA để cài vào
#   Windows/trình duyệt và dùng cho công cụ kiểm thử.
# ============================================================================
set -eu

DIR="$(cd "$(dirname "$0")" && pwd)"
FW_DIR="$DIR/../../firmware/gas-node"
CA_DAYS=3650      # CA: 10 năm
SRV_DAYS=825      # chứng chỉ máy chủ: tối đa 825 ngày (giới hạn của macOS/iOS)

if [ "$#" -eq 0 ]; then
  echo "Cách dùng: gen-certs.sh <IP-LAN> [IP/tên khác ...]   vd: gen-certs.sh 192.168.1.250 172.20.10.2" >&2
  exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
  apk add --no-cache openssl >/dev/null
fi

mkdir -p "$DIR/ca" "$DIR/server"
cd "$DIR"

# ---------------------------------------------------------------- 1. CA của nhóm
if [ -f ca/ca.key ] && [ -f ca/ca.crt ]; then
  echo "Dùng lại CA có sẵn: $(openssl x509 -in ca/ca.crt -noout -subject)"
else
  echo "Tạo CA mới (ca/ca.key, ca/ca.crt)..."
  cat > ca/ca.cnf <<'EOF'
[req]
distinguished_name = dn
prompt = no
x509_extensions = v3_ca
[dn]
O  = GasGuard
CN = GasGuard Local CA
[v3_ca]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF
  openssl req -x509 -new -newkey rsa:2048 -nodes -sha256 -days "$CA_DAYS" \
    -keyout ca/ca.key -out ca/ca.crt -config ca/ca.cnf 2>/dev/null
  rm -f ca/ca.cnf
  chmod 600 ca/ca.key
fi

# ---------------------------------------------------------------- 2. Chứng chỉ máy chủ
# Mỗi địa chỉ IP được ghi cả dạng IP (trình duyệt, Python dùng) lẫn dạng tên (mbedTLS trên ESP32 so khớp theo tên).
SAN="DNS:localhost,DNS:mosquitto,IP:127.0.0.1"
CN="$1"
for h in "$@"; do
  if echo "$h" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; then
    SAN="$SAN,IP:$h,DNS:$h"
  else
    SAN="$SAN,DNS:$h"
  fi
done

cat > server/server.cnf <<EOF
[req]
distinguished_name = dn
prompt = no
[dn]
O  = GasGuard
CN = $CN
[v3_srv]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = $SAN
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
EOF
openssl req -new -newkey rsa:2048 -nodes -sha256 -keyout server/server.key -out server/server.csr -config server/server.cnf 2>/dev/null
openssl x509 -req -in server/server.csr -CA ca/ca.crt -CAkey ca/ca.key -CAcreateserial -CAserial ca/ca.srl -sha256 -days "$SRV_DAYS" \
  -extfile server/server.cnf -extensions v3_srv -out server/server.crt 2>/dev/null
rm -f server/server.csr server/server.cnf ca/ca.srl
# Mosquitto và nginx trong container chạy bằng user khác root -> cần đọc được khóa máy chủ.
cp ca/ca.crt server/ca.crt
chmod 644 server/server.key server/server.crt server/ca.crt
openssl verify -CAfile ca/ca.crt server/server.crt >/dev/null

# ---------------------------------------------------------------- 3. CA cho firmware ESP32
if [ -d "$FW_DIR" ]; then
  {
    echo "// Tự sinh bởi deployment/certs/gen-certs.sh – chứng chỉ CA của nhóm để ESP32 kiểm tra broker."
    echo "// Không chứa bí mật (chỉ là chứng chỉ công khai) nhưng riêng cho từng triển khai -> không commit."
    echo "#pragma once"
    echo "static const char MQTT_CA_CERT[] = R\"PEM("
    cat ca/ca.crt
    echo ")PEM\";"
  } > "$FW_DIR/ca_cert.h"
  echo "Đã ghi firmware/gas-node/ca_cert.h"
fi

echo
echo "Chứng chỉ máy chủ: $(openssl x509 -in server/server.crt -noout -enddate | sed 's/notAfter=/hết hạn /')"
echo "Địa chỉ hợp lệ   : $(echo "$SAN" | tr ',' ' ')"
echo "Vân tay CA (SHA-256): $(openssl x509 -in ca/ca.crt -noout -fingerprint -sha256 | cut -d= -f2)"
echo "Xong. Khởi động lại Mosquitto và nginx để nạp chứng chỉ mới (up -d KHÔNG tự khởi động lại container đang chạy):"
echo "  dc restart mosquitto frontend      (hoặc: docker compose --env-file ../.env restart mosquitto frontend)"
