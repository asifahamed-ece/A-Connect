const { spawn, execFile } = require('child_process');
const EventEmitter = require('events');

class ScrcpyService extends EventEmitter {
  constructor(scrcpyPath = 'scrcpy', adbPath = 'adb') {
    super();
    this.scrcpyPath = scrcpyPath;
    this.adbPath = adbPath;
    this.process = null;
    this.deviceId = null;
    this.isRunning = false;
    this.stopping = false;
  }

  start(deviceId, options = {}) {
    if (this.isRunning) {
      return { success: false, message: 'Screen mirror is already running' };
    }

    const {
      // `--turn-screen-off` blanks the phone while casting. It is opt-in here
      // because it is the pair with --power-off-on-close that used to leave the
      // phone stuck on a black screen after stopping.
      turnScreenOff = false,
      stayAwake = true,
      maxFps = 60,
      bitRate = '16M',
      maxSize = 0, // 0 = native resolution
      audio = true,
      windowTitle = 'OPPO Reno 14 5G - A+ Connect',
      alwaysOnTop = false,
      fullscreen = false
    } = options;

    const args = [];

    // Target specific device if provided
    if (deviceId) {
      args.push('-s', deviceId);
    }

    // ColorOS Touch & Keyboard Bypass (UHID mode)
    // Simulates physical USB HID endpoints via Linux kernel /dev/uhid,
    // completely bypassing ColorOS permission monitoring and touch drop filters.
    args.push('--keyboard=uhid');
    args.push('--mouse=uhid');

    // Use hardware H.265 encoder for lower latency on MediaTek Dimensity
    args.push('--video-codec=h265');
    args.push('--video-encoder=c2.mtk.hevc.encoder');

    // Performance & Quality
    if (maxFps) args.push(`--max-fps=${maxFps}`);
    if (bitRate) args.push(`--video-bit-rate=${bitRate}`);
    if (maxSize > 0) args.push(`--max-size=${maxSize}`);

    // Power & Screen Management
    if (turnScreenOff) args.push('--turn-screen-off');
    if (stayAwake) args.push('--stay-awake');

    // Audio streaming
    if (!audio) {
      args.push('--no-audio');
    } else {
      args.push('--audio-buffer=40');
      args.push('--audio-codec=opus');
    }

    // Window configuration
    args.push(`--window-title=${windowTitle}`);
    if (alwaysOnTop) args.push('--always-on-top');
    if (fullscreen) args.push('--fullscreen');

    // NOTE: deliberately no --power-off-on-close. That flag powers the phone
    // display off when the client exits, and when we stop with SIGTERM the
    // phone was left on a black screen (and then dropped off Wi-Fi ADB).

    // Clipboard synchronization is enabled by default in scrcpy 5.x
    // Auto-syncs between Linux X11/Wayland clipboard and Android clipboard

    console.log('Launching scrcpy with args:', args.join(' '));

    try {
      this.process = spawn(this.scrcpyPath, args, {
        env: {
          ...process.env,
          // SDL performance hints for smoother cursor handling
          SDL_VIDEO_X11_NET_WM_BYPASS_COMPOSITOR: '0',
          SDL_MOUSE_RELATIVE_MODE_CENTER: '1',
          SDL_VIDEO_MINIMIZE_ON_FOCUS_LOSS: '0',
          // Force hardware rendering
          SDL_RENDER_DRIVER: 'opengl',
          SDL_RENDER_VSYNC: '0'
        }
      });

      this.isRunning = true;
      this.deviceId = deviceId || null;
      this.stopping = false;
      this.emit('started', { deviceId, args });

      this.process.stdout.on('data', (data) => {
        const text = data.toString();
        this.emit('log', text);
      });

      this.process.stderr.on('data', (data) => {
        const text = data.toString();
        this.emit('error-log', text);
      });

      this.process.on('close', (code) => {
        this.isRunning = false;
        this.process = null;
        const device = this.deviceId;
        this.deviceId = null;

        // Always leave the phone awake and usable, however the client ended.
        this.restoreDevice(device);

        if (!this.stopping) {
          this.stopping = false;
          this.emit('stopped', { code });
        }
      });

      this.process.on('error', (err) => {
        this.isRunning = false;
        this.process = null;
        this.emit('error', err);
      });

      return { success: true };
    } catch (err) {
      this.isRunning = false;
      this.process = null;
      return { success: false, message: err.message };
    }
  }

  // Wake the display and undo scrcpy's --stay-awake, so stopping a cast can
  // never strand the phone on a black screen.
  restoreDevice(deviceId) {
    if (!deviceId) return;

    const run = (args) => {
      execFile(this.adbPath, ['-s', deviceId, 'shell', ...args], { timeout: 5000 }, () => {});
    };

    // KEYCODE_WAKEUP (224) is a no-op when the screen is already on.
    run(['input', 'keyevent', '224']);
    run(['svc', 'power', 'stayon', 'false']);
  }

  stop() {
    if (!this.process) return { success: false, message: 'Not running' };

    this.stopping = true;
    try {
      this.process.kill('SIGTERM');
      // If the client ignores SIGTERM, make sure it cannot linger.
      setTimeout(() => {
        try {
          this.process?.kill('SIGKILL');
        } catch {}
      }, 2500).unref?.();
    } catch {}

    this.isRunning = false;
    this.process = null;
    this.emit('stopped', { code: 0 });
    return { success: true };
  }
}

module.exports = ScrcpyService;
