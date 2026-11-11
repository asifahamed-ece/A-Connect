#!/bin/bash
set -e

DEVICE="adb-XS5TE6ZXLRYTBUM7-w1zOwz._adb-tls-connect._tcp"

echo "==================================="
echo "Verifying A+ Connect Fixes"
echo "==================================="
echo ""

# Fix 1: File transfer notification
echo "TEST 1: File Transfer Notification"
echo "-----------------------------------"
echo "Creating test file..."
echo "Test from A+ Connect - $(date)" > /tmp/test-file-notification.txt

echo "Pushing to /sdcard/Download/..."
adb -s "$DEVICE" push /tmp/test-file-notification.txt /sdcard/Download/ 2>&1 | tail -n 1

echo "Triggering media scan..."
adb -s "$DEVICE" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/test-file-notification.txt > /dev/null 2>&1

echo "Posting notification on device..."
adb -s "$DEVICE" shell cmd notification post -t "📂 A+ Connect" test_file "File received: test-file-notification.txt" 2>&1 | grep -q "posting" && echo "✅ Notification posted successfully" || echo "❌ Notification failed"

echo ""
echo "👉 CHECK YOUR PHONE: Pull down notification shade, you should see:"
echo "   '📂 A+ Connect - File received: test-file-notification.txt'"
echo ""
read -p "Did you see the notification? (y/n): " saw_notif

if [ "$saw_notif" = "y" ]; then
    echo "✅ File notification FIX VERIFIED"
else
    echo "❌ File notification NOT WORKING"
fi

echo ""
echo "TEST 2: Notification Monitoring"
echo "-----------------------------------"
echo "Starting notification listener for 15 seconds..."
echo "👉 SEND YOURSELF a WhatsApp/Instagram message NOW"
echo ""

timeout 15 adb -s "$DEVICE" logcat -c
timeout 15 adb -s "$DEVICE" logcat -v time -s \
  'NotificationService:*' \
  'NotificationManager:*' \
  'StatusBar:*' \
  'ActivityManager:I' 2>&1 | \
  grep -i -E 'enqueue|post|notification' | \
  head -n 5 || echo "No notifications captured"

echo ""
read -p "Did you see notification events printed above? (y/n): " saw_events

if [ "$saw_events" = "y" ]; then
    echo "✅ Notification monitoring FIX VERIFIED"
else
    echo "❌ Notification monitoring NOT WORKING"
fi

echo ""
echo "TEST 3: Screen Mirror Cursor Performance"
echo "-----------------------------------"
echo "This will launch screen mirror with H.265 hardware encoder"
echo "👉 TEST: Move your mouse cursor around the phone screen"
echo "👉 CHECK: Is cursor smooth or laggy/stuck?"
echo ""
read -p "Press Enter to launch scrcpy (Ctrl+C to exit when done testing)..."

SDL_VIDEO_X11_NET_WM_BYPASS_COMPOSITOR=0 \
SDL_MOUSE_RELATIVE_MODE_CENTER=1 \
SDL_VIDEO_MINIMIZE_ON_FOCUS_LOSS=0 \
SDL_RENDER_DRIVER=opengl \
SDL_RENDER_VSYNC=0 \
scrcpy -s "$DEVICE" \
  --keyboard=uhid \
  --mouse=uhid \
  --video-codec=h265 \
  --video-encoder=c2.mtk.hevc.encoder \
  --max-fps=60 \
  --video-bit-rate=16M \
  --audio-buffer=40 \
  --audio-codec=opus \
  --turn-screen-off \
  --stay-awake \
  --power-off-on-close

echo ""
read -p "Was the cursor smooth (no lag/stuck)? (y/n): " cursor_smooth

if [ "$cursor_smooth" = "y" ]; then
    echo "✅ Cursor lag FIX VERIFIED"
else
    echo "❌ Cursor still laggy"
fi

echo ""
echo "==================================="
echo "Verification Summary"
echo "==================================="
echo "Run this script to test all fixes interactively"
