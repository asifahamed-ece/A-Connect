#!/bin/bash
# A+ Connect - One-Command USB Setup
# Enables wireless ADB automatically when phone is plugged in via USB

set -e

echo "🔌 A+ Connect - USB Auto-Setup"
echo "================================"
echo ""

# Check if device is connected via USB
USB_DEVICES=$(adb devices | grep -v "List of devices" | grep -v "^$" | grep -v "wifi" | wc -l)

if [ "$USB_DEVICES" -eq 0 ]; then
    echo "❌ No USB device found!"
    echo ""
    echo "Please:"
    echo "  1. Connect your Oppo Reno 14 5G via USB cable"
    echo "  2. Enable 'USB debugging' when prompted on your phone"
    echo "  3. Run this script again"
    echo ""
    exit 1
fi

# Get USB device ID
USB_DEVICE=$(adb devices | grep -v "List of devices" | grep -v "^$" | grep -v "wifi" | head -n 1 | awk '{print $1}')

echo "✅ Found device: $USB_DEVICE"
echo ""

# Enable TCP/IP mode on port 5555
echo "🔧 Enabling wireless ADB on port 5555..."
adb -s "$USB_DEVICE" tcpip 5555 2>&1 | grep -v "restarting" || true

echo "⏳ Waiting 3 seconds for ADB daemon to restart..."
sleep 3

# Get device IP address
echo "🌐 Getting device IP address..."
DEVICE_IP=$(adb -s "$USB_DEVICE" shell ip -f inet addr show wlan0 2>/dev/null | grep -oP 'inet \K[\d.]+' | head -n 1)

if [ -z "$DEVICE_IP" ]; then
    echo "❌ Could not detect device IP address"
    echo "   Make sure your phone is connected to Wi-Fi"
    exit 1
fi

echo "📱 Device IP: $DEVICE_IP"
echo ""

# Connect via Wi-Fi
echo "📡 Connecting to $DEVICE_IP:5555..."
sleep 2
adb connect "$DEVICE_IP:5555" 2>&1 | grep -v "failed to connect" || {
    sleep 3
    adb connect "$DEVICE_IP:5555"
}

echo ""
echo "✨ Setup complete!"
echo ""
echo "You can now:"
echo "  • Unplug the USB cable"
echo "  • Launch A+ Connect (npm start)"
echo "  • Your device will stay connected wirelessly"
echo ""
echo "💡 Tip: Save this IP for next time: $DEVICE_IP:5555"
echo ""
