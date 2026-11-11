#!/bin/bash
# Quick test of all three fixes

echo "Testing A+ Connect Fixes..."
echo "=============================="
echo ""

DEVICE_ID="adb-XS5TE6ZXLRYTBUM7-w1zOwz._adb-tls-connect._tcp"

# Test 1: File transfer notification
echo "1. Testing file transfer notification..."
echo "   Creating test file..."
echo "A+ Connect Test File - $(date)" > /tmp/aplus-test.txt
echo "   Pushing to phone..."
adb -s "$DEVICE_ID" push /tmp/aplus-test.txt /sdcard/Download/
echo "   Posting notification..."
adb -s "$DEVICE_ID" shell "cmd notification post -t '📂 A+ Connect' aplus_file 'File received: aplus-test.txt'"
echo "   ✅ Check your phone's notification shade"
echo ""

# Test 2: Screen mirror with H.265 hardware encoder
echo "2. Testing screen mirror (H.265 hardware encoder)..."
echo "   Launching scrcpy with optimizations..."
echo "   Press Ctrl+C to stop when done testing cursor lag"
echo ""
sleep 2

scrcpy -s "$DEVICE_ID" \
  --keyboard=uhid --mouse=uhid \
  --video-codec=h265 --video-encoder=c2.mtk.hevc.encoder \
  --max-fps=60 --video-bit-rate=16M \
  --audio-buffer=40 --audio-codec=opus \
  --window-title="Reno 14 5G - Cursor Lag Test" \
  --power-off-on-close

echo ""
echo "3. Testing notification capture..."
echo "   Send yourself a WhatsApp/Instagram message now"
echo "   Watching logcat for 30 seconds..."
echo ""

timeout 30 adb -s "$DEVICE_ID" logcat -v time -s \
  'NotificationService:*' \
  'NotificationManager:*' \
  'ActivityManager:I' | \
  grep -E 'enqueue|post|START' | \
  head -n 10

echo ""
echo "Test complete!"
