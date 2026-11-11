#!/bin/bash
# A+ Connect - One-Command USB Setup
# Enables wireless ADB automatically when phone is plugged in via USB

set -e

echo "🔌 A+ Connect - USB Auto-Setup"
echo "================================"
echo ""

# Check for any connected devices (USB or wireless)
ALL_DEVICES=$(adb devices | grep -v "List of devices" | grep -v "^$" | grep "device")

if [ -z "$ALL_DEVICES" ]; then
    echo "❌ No devices found!"
    echo ""
    echo "Please:"
    echo "  1. Connect your Oppo Reno 14 5G via USB cable"
    echo "  2. Enable 'USB debugging' when prompted on your phone"
    echo "  3. Run this script again"
    echo ""
    exit 1
fi

# Check if we have a wireless device already
WIRELESS_DEVICE=$(echo "$ALL_DEVICES" | grep ":" | head -n 1 | awk '{print $1}' || true)

if [ -n "$WIRELESS_DEVICE" ]; then
    echo "✅ Wireless device already connected: $WIRELESS_DEVICE"
    echo ""
    echo "You're already set up! Just launch A+ Connect:"
    echo "  npm start"
    echo ""
    exit 0
fi

# Find USB device (no colon in ID means USB)
USB_DEVICE=$(echo "$ALL_DEVICES" | grep -v ":" | head -n 1 | awk '{print $1}')

if [ -z "$USB_DEVICE" ]; then
    echo "❌ No USB device found (only wireless devices detected)"
    echo "If you want to set up wireless, plug USB cable first"
    exit 1
fi

echo "✅ Found USB device: $USB_DEVICE"
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
