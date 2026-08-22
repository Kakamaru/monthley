#!/usr/bin/env bash
#
# Pasang MinIO pada VPS baharu.
#
# Idempoten: boleh dijalankan semula tanpa merosakkan pemasangan sedia ada.
# Kata laluan dijana sekali dan disimpan di /root/.minio-pass — menjalankan
# semula TIDAK menggantikannya, kerana fail sedia ada dalam baldi akan
# menjadi tidak boleh dibaca.
set -e

echo "=== 1. Binari ==="
if [ ! -x /usr/local/bin/minio ]; then
  curl -fsSL https://dl.min.io/server/minio/release/linux-amd64/minio \
       -o /usr/local/bin/minio
  chmod +x /usr/local/bin/minio
fi
/usr/local/bin/minio --version | head -1

if [ ! -x /usr/local/bin/mc ]; then
  curl -fsSL https://dl.min.io/client/mc/release/linux-amd64/mc \
       -o /usr/local/bin/mc
  chmod +x /usr/local/bin/mc
fi

echo ""
echo "=== 2. Pengguna dan direktori ==="
id -u minio-user >/dev/null 2>&1 || useradd -r -s /sbin/nologin minio-user
mkdir -p /opt/minio/data
chown -R minio-user:minio-user /opt/minio

echo ""
echo "=== 3. Kata laluan ==="
if [ ! -f /root/.minio-pass ]; then
  openssl rand -base64 24 | tr -d '/+=' | head -c 24 > /root/.minio-pass
  chmod 600 /root/.minio-pass
  echo "  dijana"
else
  echo "  sudah wujud — TIDAK diganti"
fi

echo ""
echo "=== 4. Konfigurasi ==="
cat > /etc/default/minio << CFG
MINIO_ROOT_USER=monthley
MINIO_ROOT_PASSWORD=$(cat /root/.minio-pass)
MINIO_VOLUMES=/opt/minio/data
# 127.0.0.1 SAHAJA. MinIO tidak terdedah ke internet: fail awam melalui
# Nginx, muat naik melalui backend yang sudah mengesahkan pengguna.
MINIO_OPTS="--address 127.0.0.1:9000 --console-address 127.0.0.1:9001"
CFG
chmod 600 /etc/default/minio

cat > /etc/systemd/system/minio.service << 'UNIT'
[Unit]
Description=MinIO object storage
After=network-online.target
Wants=network-online.target

[Service]
Type=notify
User=minio-user
Group=minio-user
EnvironmentFile=/etc/default/minio
ExecStart=/usr/local/bin/minio server $MINIO_OPTS $MINIO_VOLUMES
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now minio
sleep 5
systemctl is-active minio

echo ""
echo "=== 5. Baldi ==="
mc alias set local http://127.0.0.1:9000 monthley "$(cat /root/.minio-pass)" >/dev/null
mc mb --ignore-existing local/monthley-public  >/dev/null
mc mb --ignore-existing local/monthley-private >/dev/null

# Baldi awam membenarkan BACA tanpa kebenaran; MENULIS tetap memerlukan
# kunci, jadi tiada siapa boleh memuat naik tanpa melalui backend.
mc anonymous set download local/monthley-public >/dev/null
mc ls local

echo ""
echo "=== 6. Kunci untuk backend ==="
echo "  Tambah ke /opt/monthley/app/monthley.env:"
echo ""
echo "    MONTHLEY_STORAGE_ENDPOINT=http://127.0.0.1:9000"
echo "    MONTHLEY_STORAGE_KEY=monthley"
echo "    MONTHLEY_STORAGE_SECRET=$(cat /root/.minio-pass)"
echo ""
echo "=== 7. Nginx ==="
echo "  Tambah blok ini SEBELUM 'location /' dalam konfigurasi tapak:"
cat << 'NGINX'

    location /files/ {
        proxy_pass http://127.0.0.1:9000/monthley-public/;
        proxy_set_header Host $host;
        proxy_cache_valid 200 1h;
        add_header Cache-Control "public, max-age=3600";
    }
NGINX
echo ""
echo "  Kemudian: nginx -t && systemctl reload nginx"
