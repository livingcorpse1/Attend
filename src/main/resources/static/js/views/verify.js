/**
 * The identity check.
 *
 * The student is asked to perform a random movement that only a live person can do.
 * The frames are sent to the server, which decides whether the movement happened, and
 * whether the face matches the enrolment.
 */

import { h, replaceChildren } from '../dom.js';
import { api } from '../api.js';
import { toast } from '../ui.js';
import { cameraPanel, openCamera, captureSequence, framesToForm } from '../camera.js';

/**
 * Runs a liveness capture.
 *
 * @param {object} options
 * @param {HTMLElement} options.panel Where the camera and the controls are rendered.
 * @param {object} options.challenge The challenge from the server.
 * @param {(frames: Array) => Promise<any>} options.submit Uploads the captured frames.
 * @param {() => void} [options.onFinished]
 * @returns {Promise<any>}
 */
export async function runLivenessCapture({ panel, challenge, submit, onFinished }) {
  const { frame, video, reticle, caption, stopButton } = cameraPanel();
  const strip = h('div.capture-strip');
  const status = h('p.text-sm.text-muted', 'Starting the camera…');
  const button = h('button.button.button-primary', { type: 'button', disabled: true }, 'Preparing…');
  const feedback = h('div');

  replaceChildren(panel, frame, status, h('div.row.gap-2.wrap', [button, stopButton]), strip, feedback);

  let session = null;
  const cleanup = () => {
    if (session) {
      session.stop();
      session = null;
    }
    caption.textContent = 'The camera is off';
    stopButton.disabled = true;
  };
  stopButton.onclick = () => {
    cleanup();
    status.textContent = 'The camera is off. Start it again when you are ready.';
    button.disabled = false;
    button.textContent = 'Start again';
  };

  try {
    session = await openCamera(video);
    caption.textContent = 'Centre your face in the frame';
    reticle.classList.add('is-locked');
    status.textContent = challenge.guidance;
    button.disabled = false;
    button.textContent = `Start · ${challenge.instruction}`;
    stopButton.disabled = false;
  } catch (failure) {
    status.textContent = '';
    feedback.replaceChildren(h('div.alert.alert-error', failure.message));
    button.textContent = 'Try the camera again';
    button.disabled = false;
    button.onclick = () => window.location.reload();
    return undefined;
  }

  button.onclick = async () => {
    button.disabled = true;
    button.textContent = 'Capturing…';
    feedback.replaceChildren();
    reticle.classList.remove('is-locked');
    status.textContent = challenge.guidance;

    try {
      const frames = await captureSequence(
        session,
        challenge.requiredFrames || 5,
        320,
        (done) => {
          status.textContent = `${challenge.instruction} · frame ${done} of ${challenge.requiredFrames || 5}`;
        }
      );
      strip.replaceChildren(...frames.map((frameImage) => h('img', { src: frameImage.dataUrl, alt: 'Captured frame' })));
      status.textContent = 'Checking your face on the server…';
      const result = await submit(frames);
      onFinished?.(result);
      return result;
    } catch (failure) {
      feedback.replaceChildren(h('div.alert.alert-error', failure.message));
      status.textContent = challenge.guidance;
      button.disabled = false;
      button.textContent = 'Try again';
      return undefined;
    } finally {
      cleanup();
      // The capture is over, so the button must not keep saying that it is capturing.
      if (button.disabled) {
        button.disabled = false;
        button.textContent = 'Try again';
      }
    }
  };

  // The caller keeps this so the camera can be released when the student navigates away.
  return { stop: cleanup };
}

/**
 * Renders the identity check as a part of the profile.
 *
 * <p>The check is not a screen of its own any more. It lives in the profile of the
 * student, where the account details and the camera check stand side by side.
 *
 * @param {object} status The biometric status of the student.
 * @param {object} options
 * @param {HTMLElement} options.container The view container, so the camera is released
 *        when the student leaves the profile.
 * @param {(account: object) => void} [options.onVerified] Called once the check succeeded.
 * @returns {HTMLElement} The section.
 */
export function identityCheckSection(status, { container, onVerified }) {
  const panel = h('div.stack.stack-3');
  const intro = h('div.stack.stack-2');
  const startButton = h('button.button.button-primary', { type: 'button' },
    status.state === 'VERIFIED' ? 'Run the check again' : 'Start the identity check');

  const start = async () => {
    startButton.disabled = true;
    try {
      const challenge = await api.post('/student/biometric/challenge', {});
      replaceChildren(intro, h('div.alert.alert-info', [
        h('div.stack.stack-2', [
          h('strong', challenge.instruction),
          h('span', challenge.guidance)
        ])
      ]));
      const capture = await runLivenessCapture({
        panel,
        challenge,
        submit: async (frames) => {
          const form = framesToForm(frames, { challengeId: challenge.challengeId });
          return api.upload('/student/biometric/enroll', form);
        },
        onFinished: async (result) => {
          if (result && result.enrolled) {
            toast('Your face is verified. Welcome to VeriQra.', 'success');
            await onVerified?.();
          } else if (result) {
            replaceChildren(intro, h('div.alert.alert-warning', result.message));
            startButton.disabled = false;
            startButton.textContent = 'Try the check again';
          }
        }
      });
      // Leaving the profile releases the camera.
      if (container) {
        container.dispose = () => capture?.stop?.();
      }
    } catch (failure) {
      replaceChildren(intro, h('div.alert.alert-error', failure.message));
      startButton.disabled = false;
    }
  };

  startButton.onclick = start;

  replaceChildren(intro, [
    h('p.eyebrow', 'Identity check'),
    h('h3', status.state === 'VERIFIED'
      ? 'Your face is verified'
      : 'Let us confirm it is really you'),
    h('p.text-sm.text-muted', status.state === 'VERIFIED'
      ? 'You can mark your attendance. Run the check again if your face has changed.'
      : 'This runs once. It takes about ten seconds and needs your camera.')
  ]);

  const steps = h('div.verify-steps', [
    step(1, 'Allow the camera', 'Your browser will ask for permission. Nothing is recorded or sent anywhere else.'),
    step(2, 'Follow the instruction', 'The server picks a random movement. A photograph cannot follow it, so it proves you are live.'),
    step(3, 'Done', 'A template of your face is stored. Your photographs are not.')
  ]);

  if (!status.available) {
    replaceChildren(intro, h('div.alert.alert-error', status.unavailableReason
      || 'The server cannot run the face check right now.'));
    startButton.disabled = true;
  }

  return h('div.stack.stack-4', [
    intro,
    h('div.row.between.wrap.gap-3', [
      h('span.text-sm.text-muted', 'Only the frames needed for the check are sent to the server.'),
      startButton
    ]),
    panel,
    steps
  ]);
}

function step(index, title, text) {
  return h('div.verify-step', [
    h('span.index', index),
    h('div', [h('h4', title), h('p', text)])
  ]);
}

/**
 * Renders the panel that lets a student review and withdraw their biometric data.
 */
export function biometricPanel(status, onRevoked) {
  const state = status.state === 'VERIFIED'
    ? h('span.badge.badge-success', 'Verified')
    : h('span.badge.badge-warning', status.state);

  const rows = [
    ['Status', state],
    ['Enrolled', status.enrolledAt ? new Date(status.enrolledAt).toLocaleString() : '—'],
    ['Model', status.modelVersion ? h('code.code-chip', status.modelVersion) : '—'],
    ['Frames used', String(status.sampleCount || 0)],
    ['Consent version', status.consentVersion || '—']
  ];

  return h('div.card', [
    h('div.card-header', [h('h3', 'Biometric data'), h('span.badge', status.available ? 'Service ready' : 'Service offline')]),
    h('div.card-body.stack.stack-4', [
      h('div.stack.stack-3', rows.map(([label, value]) => h('div.row.between', [
        h('span.text-sm.text-muted', label),
        value instanceof Node ? value : h('span.text-sm', value)
      ]))),
      status.available ? null : h('div.alert.alert-error', status.unavailableReason),
      h('div.row.between.wrap.gap-3', [
        h('span.text-sm.text-muted', 'Removing the template stops attendance marking until you run the check again.'),
        h('button.button.button-danger.button-sm', {
          type: 'button',
          onClick: async () => {
            await api.delete('/student/biometric/template');
            toast('Your biometric data has been removed.', 'success');
            onRevoked?.();
          }
        }, 'Remove my biometric data')
      ])
    ])
  ]);
}
