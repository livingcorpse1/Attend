/**
 * The student workspace: dashboard, attendance history, timetable and presence check.
 */

import { h, replaceChildren } from '../dom.js';
import { api, cachedGet, invalidate, poll } from '../api.js';
import { isCurrent, onDispose } from '../router.js';
import { currentAccount } from '../store.js';
import {
  emptyState,
  formatDateTime,
  formatPercentage,
  humanise,
  pageHead,
  statTile,
  statusBadge,
  table,
  toast
} from '../ui.js';
import { icon } from '../icons.js';
import { scannerPanel } from '../qr.js';
import { runLivenessCapture } from './verify.js';
import { createScheduler } from '../calendar.js';

/* -------------------------------------------------------------------------- */
/* Dashboard                                                                   */
/* -------------------------------------------------------------------------- */

/**
 * Renders the student dashboard.
 */
export async function renderStudentDashboard(container, route) {
  const paint = (data) => {
    if (!isCurrent(container, route)) {
      return;
    }
    const tone = data.overallPercentage >= 75 ? 'good' : data.overallPercentage >= 60 ? 'warn' : 'bad';

    const stats = h('div.stat-grid', [
      statTile({
        label: 'Overall attendance',
        value: formatPercentage(data.overallPercentage, 1),
        foot: 'Target 75% or above',
        tone,
        meter: data.overallPercentage,
        glyph: 'percent'
      }),
      statTile({
        label: 'Sessions attended',
        value: String(data.subjectsAttended),
        foot: `out of ${data.totalSessions} held`,
        glyph: 'clipboard-check'
      }),
      statTile({
        label: 'Live now',
        value: String(data.activeSessions),
        foot: 'classes taking attendance',
        glyph: 'radio'
      }),
      statTile({
        label: 'Identity check',
        value: data.biometricState === 'VERIFIED' ? 'Done' : 'Pending',
        foot: data.biometricState === 'VERIFIED' ? 'Your face template is enrolled' : 'Complete the check',
        tone: data.biometricState === 'VERIFIED' ? 'good' : 'warn',
        glyph: 'scan-face'
      })
    ]);

    const subjectTable = table(data.subjects, [
      { key: 'subjectCode', label: 'Code', render: (row) => h('span.cell-strong', row.subjectCode) },
      { key: 'subjectName', label: 'Subject' },
      { key: 'sessions', label: 'Held', align: 'right' },
      { key: 'attended', label: 'Attended', align: 'right' },
      {
        key: 'percentage',
        label: 'Attendance',
        align: 'right',
        render: (row) => h('span', {
          style: {
            color: row.percentage >= 75 ? 'var(--success)' : row.percentage >= 60 ? 'var(--warning)' : 'var(--danger)',
            fontWeight: '500'
          }
        }, formatPercentage(row.percentage))
      }
    ], { empty: 'No subjects are linked to your account yet.', emptyGlyph: 'book' });

    const recentTable = table(data.recent, [
      { key: 'recordedAt', label: 'Recorded', render: (row) => formatDateTime(row.recordedAt) },
      { key: 'status', label: 'Status', render: (row) => statusBadge(row.status) },
      { key: 'method', label: 'Method', render: (row) => humanise(row.method) },
      {
        key: 'matchScore',
        label: 'Match',
        align: 'right',
        render: (row) => (row.matchScore === null || row.matchScore === undefined
          ? '—'
          : formatPercentage(row.matchScore * 100, 0))
      }
    ], { empty: 'You have not marked any attendance yet.', emptyGlyph: 'clipboard-check' });

    container.replaceChildren(h('div.stack.stack-5', [
      pageHead({
        title: `Hello, ${firstName(currentAccount()?.fullName)}`,
        lead: 'Here is where your attendance stands today.',
        actions: h('a.button.button-primary', { href: '#/verify-presence' }, [
          icon('qr', { size: 16 }),
          h('span', 'Mark my attendance')
        ])
      }),
      stats,
      data.biometricState === 'VERIFIED' ? null : h('div.alert.alert-warning', [
        h('div.row.between.wrap.gap-3', [
          h('span', 'Your identity check is still open. Until it is done you cannot mark attendance.'),
          h('a.button.button-primary.button-sm', { href: '#/profile' }, 'Open your profile')
        ])
      ]),
      h('div.split-wide', [
        h('div.card', [
          h('div.card-header', [h('h3', 'Attendance by subject')]),
          h('div.card-body card-body-flush', subjectTable)
        ]),
        h('div.card', [
          h('div.card-header', [h('h3', 'Recent check-ins')]),
          h('div.card-body card-body-flush', recentTable)
        ])
      ]),
      h('div.card', [
        h('div.card-header', [h('h3', 'Your timetable')]),
        h('div.card-body card-body-flush', createScheduler({ slots: data.timetable, editable: false }))
      ])
    ]));
  };

  const data = await cachedGet('/student/dashboard', { onRefresh: paint });
  paint(data);

  // The numbers move while the student is looking at them, so the screen reads them
  // again on its own.
  const stop = poll('/student/dashboard', { onRefresh: paint }, 30000);
  onDispose(container, stop);
}

/* -------------------------------------------------------------------------- */
/* Attendance history                                                          */
/* -------------------------------------------------------------------------- */

/**
 * Renders the attendance history of the signed in student.
 */
export async function renderStudentAttendance(container, route) {
  const paint = (rows) => {
    if (!isCurrent(container, route)) {
      return;
    }

    const historyTable = table(rows, [
      { key: 'recordedAt', label: 'Recorded', render: (row) => formatDateTime(row.recordedAt) },
      { key: 'status', label: 'Status', render: (row) => statusBadge(row.status) },
      { key: 'method', label: 'Method', render: (row) => humanise(row.method) },
      {
        key: 'matchScore',
        label: 'Match score',
        align: 'right',
        render: (row) => (row.matchScore === null || row.matchScore === undefined
          ? '—'
          : formatPercentage(row.matchScore * 100, 0))
      }
    ], { empty: 'No attendance has been recorded yet.', emptyGlyph: 'clipboard-check' });

    container.replaceChildren(h('div.stack.stack-6', [
      pageHead({
        title: 'My attendance',
        lead: 'Every record that was created for your account, newest first.'
      }),
      h('div.card', [h('div.card-body card-body-flush', historyTable)])
    ]));
  };

  const rows = await cachedGet('/student/attendance', { onRefresh: paint });
  paint(rows);

  // The history grows while the student is looking at it.
  const stop = poll('/student/attendance', { onRefresh: paint }, 30000);
  onDispose(container, stop);
}

/* -------------------------------------------------------------------------- */
/* Timetable                                                                   */
/* -------------------------------------------------------------------------- */

/**
 * Renders the timetable of the signed in student.
 */
export async function renderStudentTimetable(container, route) {
  const paint = (slots) => {
    if (!isCurrent(container, route)) {
      return;
    }
    container.replaceChildren(h('div.stack.stack-5', [
      pageHead({
        title: 'My timetable',
        lead: 'Your weekly schedule as published by the department.'
      }),
      h('div.card', createScheduler({ slots, editable: false }))
    ]));
  };

  const slots = await cachedGet('/student/timetable', { onRefresh: paint });
  paint(slots);
}

/* -------------------------------------------------------------------------- */
/* Verify presence                                                             */
/* -------------------------------------------------------------------------- */

/**
 * Renders the presence check.
 *
 * The student scans the classroom code, performs the movement the server asks for, and
 * the server records the attendance.
 */
export async function renderVerifyPresence(container, route) {
  const sessions = await cachedGet('/student/sessions/active');
  if (!isCurrent(container, route)) {
    return;
  }

  const result = h('div');
  const panel = h('div.card.card-pad.stack.stack-4');
  const scanner = scannerPanel();

  const begin = async (token) => {
    replaceChildren(result);
    let ticket;
    try {
      ticket = await api.post('/student/attendance/scan', { token });
    } catch (failure) {
      replaceChildren(result, h('div.alert.alert-error', failure.message));
      return;
    }

    replaceChildren(result, h('div.card.card-pad.stack.stack-4', [
      h('div.alert.alert-success', [
        h('div.stack.stack-1', [
          h('strong', `${ticket.subjectCode} · ${ticket.subjectName}`),
          h('span', `${ticket.section} · ${ticket.room}${ticket.facultyName ? ` · ${ticket.facultyName}` : ''}`)
        ])
      ]),
      h('div.card.card-pad.stack.stack-4', [
        h('h3', 'Confirm your presence'),
        h('p.text-sm.text-muted', ticket.challengeGuidance),
        h('div#capture-panel')
      ])
    ]));

    const capture = await runLivenessCapture({
      panel: result.querySelector('#capture-panel'),
      challenge: {
        challengeId: ticket.challengeId,
        instruction: ticket.challengeInstruction,
        guidance: ticket.challengeGuidance,
        requiredFrames: ticket.requiredFrames
      },
      submit: async (frames) => {
        const form = new FormData();
        frames.forEach((frame, index) => form.append('frames', frame.blob, `frame-${index}.jpg`));
        form.append('ticketId', ticket.ticketId);
        return api.upload('/student/attendance/submit', form);
      },
      onFinished: (submission) => {
        if (!submission) {
          return;
        }
        if (submission.outcome === 'RECORDED') {
          toast('Your attendance has been recorded.', 'success');
          // The history and the dashboard changed, so they are read again.
          invalidate('/student/attendance');
          invalidate('/student/dashboard');
          replaceChildren(result, h('div.card.card-pad.stack.stack-4', [
            h('div.alert.alert-success', [
              h('div.stack.stack-2', [
                h('strong', 'Attendance recorded'),
                h('span', submission.message),
                h('span.text-xs', `Face match ${formatPercentage((submission.matchScore || 0) * 100, 0)} · liveness ${formatPercentage((submission.livenessScore || 0) * 100, 0)}`)
              ])
            ]),
            h('a.button.button-primary', { href: '#/dashboard' }, [
              icon('arrow-left', { size: 16 }),
              h('span', 'Back to the dashboard')
            ])
          ]));
        } else {
          replaceChildren(result, h('div.alert.alert-error', submission.message));
        }
      }
    });


    // Leaving the screen releases the camera.
    onDispose(container, () => capture?.stop?.());
  };

  const onQr = async (event) => {
    scanner.stop();
    await begin(event.detail);
  };
  window.addEventListener('veriqra:qr', onQr, { once: true });

  replaceChildren(panel, [
    h('div.card-header', [h('h3', 'Step 1 · Scan the classroom code')]),
    h('div.card-body.stack.stack-4', [
      h('p.text-sm.text-muted', 'The code your teacher is showing changes every second and stops working within seconds of being captured. Aim the camera at the screen.'),
      scanner.panel
    ])
  ]);


  /**
   * The list of the classes that are taking attendance right now.
   */
  const liveHost = h('div');
  const paintLive = (fresh) => {
    if (!isCurrent(container, route)) {
      return;
    }
    replaceChildren(liveHost, fresh.length === 0
      ? h('div.card.card-pad', emptyState('No class is taking attendance at the moment. Check again during your lecture.', 'radio'))
      : h('div.card', [
        h('div.card-header', [h('h3', 'Classes taking attendance right now')]),
        h('div.card-body card-body-flush', table(fresh, [
          { key: 'subjectCode', label: 'Code', render: (row) => h('span.cell-strong', row.subjectCode) },
          { key: 'subjectName', label: 'Subject' },
          { key: 'section', label: 'Section' },
          { key: 'room', label: 'Room' },
          { key: 'facultyName', label: 'Teacher' }
        ]))
      ]));
  };
  paintLive(sessions);

  container.replaceChildren(h('div.stack.stack-6', [
    pageHead({
      title: 'Verify your presence',
      lead: 'Scan the classroom code and complete the camera check. Both have to succeed.'
    }),
    h('div.split-wide', [panel, h('div.stack.stack-4', [liveHost])]),
    result
  ]));

  // A class may start while the student is on this screen, so the list is read again.
  const stop = poll('/student/sessions/active', { onRefresh: paintLive }, 20000);
  onDispose(container, () => {
    stop();
    scanner.stop();
    window.removeEventListener('veriqra:qr', onQr);
  });
}

/* -------------------------------------------------------------------------- */
/* Helpers                                                                      */
/* -------------------------------------------------------------------------- */

function firstName(fullName) {
  if (!fullName) {
    return 'there';
  }
  return String(fullName).trim().split(/\s+/)[0];
}