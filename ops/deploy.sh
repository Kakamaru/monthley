#!/usr/bin/env bash
set -e
cd ~/MONTHLEY/monthley-backend

echo "=== bina ==="
mvn -q clean package -DskipTests 2>&1 | tail -3

echo "=== hantar ke lokasi sementara ==="
# JAR TIDAK ditulis terus ke atas fail yang sedang berjalan. JVM memuatkan
# kelas secara malas dari JAR, jadi menukar fail di bawah proses yang hidup
# menyebabkan NoClassDefFoundError untuk kelas yang belum dimuatkan —
# gejalanya muncul sebagai ralat rawak yang tidak berkaitan.
scp target/monthley-backend-*.jar root@217.216.32.48:/opt/monthley/app/monthley.jar.new

echo "=== hentikan, tukar, mula ==="
ssh root@217.216.32.48 'bash -s' << 'REMOTE'
systemctl stop monthley
sleep 2
mv /opt/monthley/app/monthley.jar.new /opt/monthley/app/monthley.jar
systemctl start monthley
sleep 25
systemctl is-active monthley
grep "Started MonthleyApplication" /opt/monthley/logs/monthley.log | tail -1
REMOTE
