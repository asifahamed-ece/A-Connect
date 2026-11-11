const { spawn } = require('child_process');
const EventEmitter = require('events');

class ScrcpyService extends EventEmitter {
  constructor(scrcpyPath = 'scrcpy') {
    super();
    this.scrcpyPath = scrcpyPath;
    this.process = null;
    this.isRunning = false;
  }

  start(deviceId, options = {}) {
    if (this.isRunning) {
      return { success: false, message: 'Screen mirror is already running' };
    }

    const {
      turnScreenOff = true,
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

    // Disable display on start (saves power)
    args.push('--power-off-on-close');

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
        this.emit('stopped', { code });
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

  stop() {
    if (this.process) {
      this.process.kill('SIGTERM');
      this.isRunning = false;
      this.process = null;
      this.emit('stopped', { code: 0 });
      return { success: true };
    }
    return { success: false, message: 'Not running' };
  }
}

module.exports = ScrcpyService;
