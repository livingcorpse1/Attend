/**
 * The identity check.
 *
 * The student is asked to perform a random movement that only a live person can do.
 * The frames are sent to the server, which decides whether the movement happened, and
 * whether the face matches the enrolment.
 */

import { h, replaceChildren } from '../dom.js';
import { api } from '../api.js';
import { isCurrent } from '../router.js';
import { pageHead, toast } from '../ui.js';
import { icon } from '../icons.js';
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
  const { frame, video, reticle, caption } = cameraPanel();
  const strip = h('div.capture-strip');
  const status = h('p.text-sm.text-muted', 'Starting the camera…');
  const button = h('button.button.button-primary', { type: 'button', disabled: true }, 'Preparing…');
  const feedback = h('div');

  replaceChildren(panel, frame, status, button, strip, feedback);

  let session = null;
  const cleanup = () => {
    if (session) {
      session.stop();
      session = null;
    }
  };

  try {
    session = await openCamera(video);
    caption.textContent = 'Centre your face in the frame';
    reticle.classList.add('is-locked');
    status.textContent = challenge.guidance;
    button.disabled = false;
    button.textContent = `Start · ${challenge.instruction}`;
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
    }
  };

  return undefined;
}

/**
 * Renders the identity check page.
 *
 * @param {HTMLElement} container
 * @param {object} options
 * @param {(account: object) => void} options.onVerified Called once the check succeeded.
 */
export async function renderVerification(container, route, { onVerified }) {
  const status = await api.get('/student/biometric/status');
  if (!isCurrent(container, route)) {
    return;
  }
  const panel = h('div.card.card-pad.stack.stack-4', h('div.empty-state', [
    h('div.glyph', icon('scan-face', { size: 20 })),
    h('p', 'The camera preview appears here once the check starts.'),
    h('p.text-sm.text-subtle', 'Nothing is recorded. Only the frames needed for the check are sent to the server.')
  ]));
  const intro = h('div.stack.stack-2');

  const start = async () => {
    try {
      const challenge = await api.post('/student/biometric/challenge', {});
      replaceChildren(intro, h('div.alert.alert-info', [
        h('div.stack.stack-2', [
          h('strong', challenge.instruction),
          h('span', challenge.guidance)
        ])
      ]));
      await runLivenessCapture({
        panel,
        challenge,
        submit: async (frames) => {
          const form = framesToForm(frames, { challengeId: challenge.challengeId });
          return api.upload('/student/biometric/enroll', form);
        },
        onFinished: async (result) => {
          if (result && result.enrolled) {
            toast('Your face is verified. Welcome to VeriQra.', 'success');
            await onVerified();
          } else if (result) {
            replaceChildren(intro, h('div.alert.alert-warning', result.message));
            startButton.disabled = false;
            startButton.textContent = 'Try the check again';
          }
        }
      });
    } catch (failure) {
      replaceChildren(intro, h('div.alert.alert-error', failure.message));
    }
  };

  const startButton = h('button.button.button-primary.button-lg', { type: 'button', onClick: start }, [
    icon('scan-face', { size: 16 }),
    h('span', status.state === 'VERIFIED' ? 'Re-run the identity check' : 'Start the identity check')
  ]);

  replaceChildren(intro, [
    h('p.eyebrow', 'Identity check'),
    h('h1', 'Let us confirm it is really you'),
    h('p.lead', 'This runs once. It takes about ten seconds and needs your camera.')
  ]);

  const steps = h('div.verify-steps', [
    step(1, 'Allow the camera', 'Your browser will ask for permission. Nothing is recorded or sent anywhere else.'),
    step(2, 'Follow the instruction', 'The server picks a random movement. A photograph cannot follow it, so it proves you are live.'),
    step(3, 'Done', 'A template of your face is stored. Your photographs are not.')
  ]);

  container.replaceChildren(h('div.stack.stack-6', [
    pageHead({
      title: 'Identity check',
      lead: 'Required once before you can mark attendance.',
      actions: startButton
    }),
    h('div.split-wide.split-top', [
      h('div.card.card-pad.stack.stack-5', [intro, steps]),
      h('div.card.card-pad.stack.stack-4', [
        h('h3', 'Camera'),
        panel,
        h('div.alert.alert-warning', [
          h('span', 'Your face template can be removed at any time from the Verify page. '
            + 'Removing it stops you from marking attendance until you run the check again.')
        ])
      ])
    ])
  ]));

  if (!status.available) {
    replaceChildren(intro, h('div.alert.alert-error', status.unavailableReason
      || 'The server cannot run the face check right now.'));
    startButton.disabled = true;
  }
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
