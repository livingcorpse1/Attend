/**
 * QR scanning and rendering.
 *
 * The scanner uses jsQR and the renderer uses qrcodejs. Both libraries are served from
 * this origin so the application works offline and does not leak usage data to a CDN.
 */

import { h } from './dom.js';

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
export function scannerPanel() {
  const video = h('video', { playsinline: '', muted: '', autoplay: '' });
  const canvas = h('canvas', { hidden: true });
  const status = h('p.text-sm.text-muted', 'Point the camera at the code your teacher is showing.');
  const panel = h('div.stack.stack-3', [video, status, canvas]);

  let stream = null;
  let frameHandle = null;

  async function start() {
    await waitForLibrary('jsQR');
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error('This browser cannot access the camera.');
    }
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' } },
      audio: false
    });
    video.srcObject = stream;
    video.setAttribute('playsinline', '');
    video.muted = true;
    await video.play();
    status.textContent = 'Scanning for the classroom code…';
    await scan();
  }

  async function scan() {
    const context = canvas.getContext('2d', { willReadFrequently: true });
    const decode = (source, width, height) => {
      const imageData = context.getImageData(0, 0, width, height);
      return window.jsQR(imageData.data, width, height, { inversionAttempts: 'attemptBoth' });
    };

    const tick = () => {
      if (!stream) {
        return;
      }
      if (video.readyState === video.HAVE_ENOUGH_DATA) {
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        context.drawImage(video, 0, 0, canvas.width, canvas.height);
        const result = decode(canvas, canvas.width, canvas.height);
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

  function stop() {
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
  }

  return { panel, video, canvas, status, start, stop };
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
