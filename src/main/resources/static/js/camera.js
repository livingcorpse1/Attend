/**
 * Camera access and frame capture.
 *
 * The browser is only ever a source of pixels. Frames are captured as JPEG blobs and
 * posted to the server, where detection, liveness and matching happen.
 */

import { h } from './dom.js';

/** The quality used for the captured frames. */
const JPEG_QUALITY = 0.82;

/** The maximum edge length of a captured frame, to keep uploads small. */
const MAX_EDGE = 960;

/**
 * A live camera session.
 */
export class CameraSession {
  constructor(video, stream) {
    this.video = video;
    this.stream = stream;
  }

  /** Stops every track and releases the camera. */
  stop() {
    for (const track of this.stream.getTracks()) {
      track.stop();
    }
    this.video.srcObject = null;
  }

  /**
   * Captures the current frame.
   *
   * @returns {Promise<{blob: Blob, dataUrl: string, width: number, height: number}>}
   */
  async capture() {
    const video = this.video;
    if (!video.videoWidth || !video.videoHeight) {
      throw new Error('The camera is not ready yet.');
    }
    const scale = Math.min(1, MAX_EDGE / Math.max(video.videoWidth, video.videoHeight));
    const width = Math.round(video.videoWidth * scale);
    const height = Math.round(video.videoHeight * scale);

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    context.drawImage(video, 0, 0, width, height);

    const dataUrl = canvas.toDataURL('image/jpeg', JPEG_QUALITY);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY));
    if (!blob) {
      throw new Error('The frame could not be captured.');
    }
    return { blob, dataUrl, width, height };
  }
}

/**
 * Explains why the camera is not available.
 *
 * <p>The usual reason on a phone is that the address of the site is not trusted: the
 * browser then does not treat the page as a secure origin and hands no camera to it,
 * even when the certificate was accepted with "proceed anyway". The certificate of the
 * college has to be installed on the device for that.
 *
 * @returns {string} The message to show.
 */
export function cameraUnavailableMessage() {
  if (window.isSecureContext === false) {
    return 'This address is not trusted by the browser, so the browser hands no camera to it. '
      + 'Install the certificate of the college on this device (veriqra-certificate.cer, '
      + 'Settings > Security > Install from storage on Android) and open the site again.';
  }
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    return 'This browser cannot access the camera. Use a modern browser over HTTPS.';
  }
  return 'The camera is not available on this device.';
}

/**
 * Opens the camera.
 *
 * @param {HTMLVideoElement} video
 * @param {object} [options]
 * @param {boolean} [options.facingMode] Use the front camera by default.
 * @returns {Promise<CameraSession>}
 */
export async function openCamera(video, options = {}) {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    throw new Error(cameraUnavailableMessage());
  }
  const facingMode = options.facingMode === false ? 'environment' : 'user';
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: facingMode },
        width: { ideal: 1280 },
        height: { ideal: 960 },
        frameRate: { ideal: 30 }
      },
      audio: false
    });
  } catch (error) {
    if (error.name === 'NotAllowedError') {
      throw new Error('Camera permission was denied. Allow camera access and try again.');
    }
    if (error.name === 'NotFoundError') {
      throw new Error('No camera was found on this device.');
    }
    throw new Error('The camera could not be started: ' + error.message);
  }

  video.srcObject = stream;
  video.setAttribute('playsinline', '');
  video.muted = true;
  await video.play();
  await waitForMetadata(video);
  return new CameraSession(video, stream);
}

/**
 * Waits until the video element reports its dimensions.
 */
function waitForMetadata(video) {
  if (video.videoWidth > 0) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const onReady = () => {
      video.removeEventListener('loadedmetadata', onReady);
      resolve();
    };
    video.addEventListener('loadedmetadata', onReady);
  });
}

/**
 * Builds the camera panel used by the verification screens.
 *
 * <p>The panel shows the camera inside a frame and carries a button that releases it
 * again, so the camera light always goes out when the student is finished.
 */
export function cameraPanel() {
  const video = h('video', { playsinline: '', muted: '', autoplay: '' });
  const reticle = h('div.reticle');
  const caption = h('div.caption', 'The camera is off');
  const frame = h('div.camera-frame', [video, h('div.overlay', reticle), caption]);
  const stopButton = h('button.button.button-secondary', { type: 'button', disabled: true }, 'Stop the camera');
  return { frame, video, reticle, caption, stopButton };
}

/**
 * Captures a sequence of frames with a pause between them.
 *
 * @param {CameraSession} session
 * @param {number} count How many frames to take.
 * @param {number} intervalMillis Pause between frames.
 * @param {(progress: number) => void} [onProgress]
 * @returns {Promise<Array<{blob: Blob, dataUrl: string}>>}
 */
export async function captureSequence(session, count, intervalMillis, onProgress) {
  const frames = [];
  for (let index = 0; index < count; index += 1) {
    if (index > 0) {
      await delay(intervalMillis);
    }
    frames.push(await session.capture());
    if (onProgress) {
      onProgress(index + 1);
    }
  }
  return frames;
}

/** Pauses for the given number of milliseconds. */
export function delay(milliseconds) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

/**
 * Builds a multipart body from captured frames.
 */
export function framesToForm(frames, fields = {}) {
  const form = new FormData();
  frames.forEach((frame, index) => {
    form.append('frames', frame.blob, `frame-${index}.jpg`);
  });
  for (const [key, value] of Object.entries(fields)) {
    if (value !== undefined && value !== null) {
      form.append(key, String(value));
    }
  }
  return form;
}
