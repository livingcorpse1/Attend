/**
 * QR scanning and rendering.
 *
 * The scanner uses jsQR and the renderer uses qrcodejs. Both libraries are served from
 * this origin so the application works offline and does not leak usage data to a CDN.
 */

import { h } from './dom.js';
import { cameraUnavailableMessage, playVideo, wasInterrupted } from './camera.js';

/**
 * Waits until the scanner library is available.
 */
async function waitForLibrary(name, timeoutMillis = 8000) {
  const started = Date.now();
  while (!window[name]) {
    if (Date.now() - started > timeoutMillis) {
      throw new Error(`The QR library (${name}) could not be loaded.`);
    }
    await new Promise((resolve) => window.setTimeout(resolve, 40));
  }
}

/**
 * Builds a QR scanner panel.
 *
 * @returns {{panel: HTMLElement, video: HTMLVideoElement, canvas: HTMLCanvasElement, start: Function, stop: Function}}
 */
/**
 * Builds the QR scanner panel.
 *
 * <p>The panel shows the camera inside a frame, so it is obvious what the phone is
 * looking at, and it carries a button that releases the camera again.
 *
 * @returns {{panel: HTMLElement, video: HTMLVideoElement, canvas: HTMLCanvasElement,
 *            status: HTMLElement, start: Function, stop: Function, isRunning: Function}}
 */
export function scannerPanel() {
  const video = h('video', { playsinline: '', muted: '', autoplay: '' });
  const canvas = h('canvas', { hidden: true });
  const status = h('p.text-sm.text-muted', 'Point the camera at the code your teacher is showing.');
  const reticle = h('div.reticle');
  const caption = h('div.caption', 'The camera is off');
  const frame = h('div.camera-frame.is-scanning', [video, canvas, h('div.overlay', reticle), caption]);

  const startButton = h('button.button.button-primary', { type: 'button' }, 'Start the camera');
  const stopButton = h('button.button.button-secondary', { type: 'button', disabled: true }, 'Stop the camera');

  const panel = h('div.stack.stack-3', [frame, h('div.row.gap-2.wrap', [startButton, stopButton]), status]);

  let stream = null;
  let frameHandle = null;
  let running = false;
  let starting = false;
  let startToken = 0;

  async function scan() {
    const context = canvas.getContext('2d', { willReadFrequently: true });

    const tick = () => {
      if (!running) {
        return;
      }
      if (video.readyState === video.HAVE_ENOUGH_DATA) {
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        context.drawImage(video, 0, 0, canvas.width, canvas.height);
        const imageData = context.getImageData(0, 0, canvas.width, canvas.height);
        const result = window.jsQR(imageData.data, canvas.width, canvas.height,
          { inversionAttempts: 'attemptBoth' });
        if (result && result.data) {
          stop();
          status.textContent = 'Code captured. Hold on…';
          window.dispatchEvent(new CustomEvent('veriqra:qr', { detail: result.data }));
          return;
        }
      }
      frameHandle = window.requestAnimationFrame(tick);
    };
    frameHandle = window.requestAnimationFrame(tick);
  }

  async function start() {
    if (running || starting) {
      return;
    }
    // The camera takes a moment to open. The button is put to sleep for that time, so
    // a second tap cannot start a second camera on top of the first one.
    starting = true;
    const token = ++startToken;
    startButton.disabled = true;
    try {
      await waitForLibrary('jsQR');
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error(cameraUnavailableMessage());
      }
      const opened = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' } },
        audio: false
      });
      if (token !== startToken) {
        // The camera was stopped while it was opening.
        for (const track of opened.getTracks()) {
          track.stop();
        }
        return;
      }
      stream = opened;
      running = true;
      video.srcObject = stream;
      video.setAttribute('playsinline', '');
      video.muted = true;
      await playVideo(video);
      caption.textContent = 'Scanning for the classroom code';
      status.textContent = 'Hold the code inside the frame.';
      stopButton.disabled = false;
      scan();
    } catch (failure) {
      // The camera is not running, so the tracks are released and the button is free
      // again. A start that was interrupted is not a failure of the camera.
      if (stream) {
        for (const track of stream.getTracks()) {
          track.stop();
        }
        stream = null;
      }
      video.srcObject = null;
      running = false;
      startButton.disabled = false;
      if (!wasInterrupted(failure)) {
        throw failure;
      }
    } finally {
      starting = false;
    }
  }

  function stop() {
    running = false;
    starting = false;
    // A start that is still on its way is cancelled, so the camera does not open after
    // the person walked away from it.
    startToken += 1;
    if (frameHandle) {
      window.cancelAnimationFrame(frameHandle);
      frameHandle = null;
    }
    if (stream) {
      for (const track of stream.getTracks()) {
        track.stop();
      }
      stream = null;
    }
    video.srcObject = null;
    caption.textContent = 'The camera is off';
    status.textContent = 'The camera is off. Start it again when you are ready.';
    startButton.disabled = false;
    stopButton.disabled = true;
  }

  startButton.onclick = () => {
    start().catch((failure) => {
      status.textContent = '';
      status.replaceWith(h('div.alert.alert-error', failure.message));
    });
  };
  stopButton.onclick = stop;

  return {
    panel,
    video,
    canvas,
    status,
    start,
    stop,
    isRunning: () => running
  };
}

/**
 * Renders a QR code for a payload.
 *
 * @param {string} payload
 * @param {object} [options]
 * @param {number} [options.size] Pixel size of the square code.
 * @returns {HTMLElement}
 */
export function renderQr(payload, options = {}) {
  const size = options.size || 220;
  const container = h('div.qr-stage');
  const mount = h('div');
  container.append(mount);

  const draw = () => {
    mount.replaceChildren();
    if (!window.QRCode) {
      mount.append(h('p.text-sm.text-muted', 'The QR library is still loading.'));
      return;
    }
    new window.QRCode(mount, {
      text: payload,
      width: size,
      height: size,
      colorDark: '#0b1224',
      colorLight: '#ffffff',
      correctLevel: window.QRCode.CorrectLevel.M
    });
  };

  if (window.QRCode) {
    draw();
  } else {
    waitForLibrary('QRCode').then(draw).catch(() => {
      mount.append(h('p.text-sm.text-muted', 'The QR library could not be loaded.'));
    });
  }
  return container;
}
