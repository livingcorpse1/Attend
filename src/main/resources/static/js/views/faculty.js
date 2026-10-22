/**
 * The teacher workspace: dashboard, live attendance and timetable.
 */

import { h, replaceChildren } from '../dom.js';
import { isCurrent, onDispose } from '../router.js';
import { api, cachedGet, invalidate, poll } from '../api.js';
import { onChange, watchAccount } from '../live.js';
import { currentAccount } from '../store.js';
import {
  confirmDialog,
  emptyState,
  formatDateTime,
  formatPercentage,
  humanise,
  openModal,
  pageHead,
  statTile,
  statusBadge,
  table,
  toast
} from '../ui.js';
import { icon } from '../icons.js';
import { renderQr } from '../qr.js';
import { createCalendar, createScheduler } from '../calendar.js';

/* -------------------------------------------------------------------------- */
/* Dashboard                                                                   */
/* -------------------------------------------------------------------------- */

/**
 * Renders the teacher dashboard.
 */
export async function renderFacultyDashboard(container, route) {
  const paint = (data) => {
    if (!isCurrent(container, route)) {
      return;
    }
    const tone = data.overallPercentage >= 75 ? 'good' : data.overallPercentage >= 60 ? 'warn' : 'bad';

    const stats = h('div.stat-grid', [
      statTile({
        glyph: 'percent',
        label: 'Attendance recorded',
        value: formatPercentage(data.overallPercentage, 1),
        foot: 'Across every subject you teach',
        tone,
        meter: data.overallPercentage
      }),
      statTile({ glyph: 'radio', label: 'Sessions held', value: String(data.sessionsHeld), foot: 'Since the semester started' }),
      statTile({ glyph: 'check', label: 'Records marked', value: String(data.recordsMarked), foot: 'Present entries recorded' }),
      statTile({ glyph: 'book', label: 'Classes taught', value: String(data.classesTaught), foot: 'Subjects assigned to you' })
    ]);

    const subjectTable = table(data.subjects, [
      { key: 'subjectCode', label: 'Code' },
      { key: 'subjectName', label: 'Subject' },
      { key: 'sessions', label: 'Sessions', align: 'right' },
      { key: 'attended', label: 'Present', align: 'right' },
      { key: 'percentage', label: 'Rate', align: 'right', render: (row) => formatPercentage(row.percentage) }
    ], { empty: 'No sessions have been taken yet.' });

    const sessionTable = table(data.recentSessions, [
      { key: 'startedAt', label: 'Started', render: (row) => formatDateTime(row.startedAt) },
      { key: 'subjectCode', label: 'Class', render: (row) => `${row.subjectCode} · ${row.section}` },
      { key: 'status', label: 'Status', render: (row) => statusBadge(row.status) },
      { key: 'presentCount', label: 'Present', align: 'right' },
      { key: 'enrolledCount', label: 'Enrolled', align: 'right' }
    ], { empty: 'You have not run a session yet.' });

    const calendar = h('div.card', createCalendar({
      slots: data.timetable,
      onSlotClick: () => {
        window.location.hash = '#/timetable';
      }
    }));

    container.replaceChildren(h('div.stack.stack-5', [
      pageHead({
        title: 'Teaching dashboard',
        lead: 'Attendance across your classes, and who needs attention.',
        actions: h('a.button.button-primary', { href: '#/attendance' }, [
          icon('radio', { size: 16 }),
          h('span', 'Take attendance')
        ])
      }),
      stats,
      calendar,
      h('div.split-wide', [
        h('div.card', [
          h('div.card-header', [h('h3', 'Attendance by subject')]),
          h('div.card-body card-body-flush', subjectTable)
        ]),
        h('div.card', [
          h('div.card-header', [h('h3', 'Recent sessions')]),
          h('div.card-body card-body-flush', sessionTable)
        ])
      ])
    ]));
  };

  const data = await cachedGet('/faculty/dashboard', { onRefresh: paint });
  paint(data);

  // The numbers on the dashboard move while the teacher is looking at them, so the
  // screen reads them again on its own.
  const stop = poll('/faculty/dashboard', { onRefresh: paint }, 30000);
  onDispose(container, stop);
}

/* -------------------------------------------------------------------------- */
/* Live attendance                                                             */
/* -------------------------------------------------------------------------- */

/**
 * Renders the live attendance screen.
 *
 * <p>Attendance is only taken for a lecture that is on the teaching diary of the day.
 * The screen therefore lists the lectures of the day and offers to start only those
 * that are running.
 */
export async function renderFacultyAttendance(container, route) {
  const [lectures, sessions] = await Promise.all([
    cachedGet('/faculty/schedule'),
    cachedGet('/faculty/sessions', { params: { status: 'ACTIVE' } })
  ]);
  if (!isCurrent(container, route)) {
    return;
  }
  const sessionArea = h('div');
  // The sessions of the teacher, so a lecture can say whether its attendance is running.
  let openSessions = sessions.slice();
  // The session that is shown on the right.
  let shown = null;

  const resume = (session) => {
    shown = session;
    renderSession(session);
  };

  /**
   * The screen without a running session.
   */
  const showNothing = () => {
    shown = null;
    replaceChildren(sessionArea, h('div.card.card-pad', emptyState(
      'No session is running. Start one to project the classroom code.', 'radio')));
  };

  /**
   * Starts the attendance session of a lecture.
   */
  const start = async (lecture, button) => {
    button.disabled = true;
    try {
      const session = await api.post('/faculty/sessions', undefined, { params: { slotId: lecture.id } });
      // The list of sessions and the dashboard changed.
      invalidate('/faculty/sessions');
      invalidate('/faculty/dashboard');
      invalidate('/faculty/schedule');
      toast(`Attendance started for ${lecture.subjectCode} · ${lecture.section}.`, 'success');
      openSessions = [session, ...openSessions.filter((entry) => entry.id !== session.id)];
      paintLectures(lectures);
      resume(session);
    } catch (failure) {
      toast(failure.message, 'error');
      button.disabled = false;
    }
  };

  /**
   * One lecture of the day, with the state of its attendance.
   */
  const lectureRow = (lecture) => {
    const running = openSessions.find((entry) => Number(entry.classId) === Number(lecture.classId));
    if (running) {
      const open = h('button.button.button-secondary.button-sm', { type: 'button' }, 'Attendance is running');
      open.onclick = () => resume(running);
      return h('div.lecture-row', [
        h('div.stack.stack-1', [
          h('strong', `${lecture.subjectCode} · ${lecture.subjectName}`),
          h('span.text-sm.text-muted', `${lecture.section} · ${lecture.room} · ${lecture.starts} – ${lecture.ends}`)
        ]),
        h('div.lecture-action', [
          h('span.badge.badge-success', 'Live'),
          open
        ])
      ]);
    }

    const button = h('button.button.button-primary', { type: 'button' }, 'Start attendance');
    button.disabled = !lecture.open;
    button.onclick = () => start(lecture, button);
    return h('div.lecture-row', [
      h('div.stack.stack-1', [
        h('strong', `${lecture.subjectCode} · ${lecture.subjectName}`),
        h('span.text-sm.text-muted', `${lecture.section} · ${lecture.room} · ${lecture.starts} – ${lecture.ends}`)
      ]),
      h('div.lecture-action', [
        lecture.open ? null : h('span.text-xs.text-muted', lecture.message || ''),
        button
      ])
    ]);
  };

  const setupCard = h('div.card', [
    h('div.card-header', [
      h('h3', "Today's lectures"),
      h('span.text-xs.text-muted', 'Attendance follows the timetable')
    ]),
    h('div.card-body.stack.stack-3', lectures.length === 0
      ? [emptyState('You have no lectures scheduled today, so there is no attendance to take.', 'book')
      ]
      : lectures.map(lectureRow))
  ]);

  /**
   * Redraws the lectures of the day, so a lecture that starts while the screen is
   * open becomes available without a refresh.
   */
  const paintLectures = (fresh) => {
    if (!isCurrent(container, route)) {
      return;
    }
    const body = setupCard.querySelector('.card-body');
    replaceChildren(body, fresh.length === 0
      ? [emptyState('You have no lectures scheduled today, so there is no attendance to take.', 'book')]
      : fresh.map(lectureRow));
  };

  container.replaceChildren(h('div.stack.stack-6', [
    pageHead({
      title: 'Take attendance',
      lead: 'Pick a lecture of the day, project the rotating code and watch the roster fill in as students check in.'
    }),
    h('div.split-wide', [setupCard, sessionArea])
  ]));

  if (sessions.length > 0) {
    resume(sessions[0]);
  } else {
    showNothing();
  }

  // The lectures of the day are read again, so a teacher who opens the screen before
  // their lecture starts finds it there.
  const stop = poll('/faculty/schedule', { onRefresh: paintLectures }, 30000);
  onDispose(container, stop);

  /**
   * Renders the running session: the rotating code and the live roster.
   */
  function renderSession(session) {
    let timer = null;
    let rosterTimer = null;
    let closed = false;

    const qrHost = h('div');
    const countdown = h('span.qr-timer', '');
    const liveState = h('span.live-dot', 'Live');
    const rosterHost = h('div', emptyState('Loading the roster…', 'clock'));
    const endButton = h('button.button.button-danger#end', { type: 'button' }, 'End session');

    const stop = () => {
      closed = true;
      if (timer) {
        window.clearInterval(timer);
      }
      if (rosterTimer) {
        window.clearInterval(rosterTimer);
      }
    };

    const refreshQr = async () => {
      try {
        const payload = await api.get(`/faculty/sessions/${session.id}/qr`);
        replaceChildren(qrHost, renderQr(payload.token, { size: 220 }));
        const remaining = Math.max(0, payload.expiresAt - Math.floor(Date.now() / 1000));
        countdown.textContent = `refreshes in ${payload.refreshSeconds}s · valid for ${remaining}s`;
      } catch (failure) {
        countdown.textContent = failure.message;
        stop();
      }
    };

    const refreshRoster = async () => {
      try {
        const roster = await api.get(`/faculty/sessions/${session.id}/roster`);
        replaceChildren(rosterHost, rosterTable(roster));
      } catch (failure) {
        if (!closed) {
          replaceChildren(rosterHost, h('div.alert.alert-error', failure.message));
        }
      }
    };

    const rosterTable = (roster) => table(roster, [
      { key: 'studentName', label: 'Student' },
      { key: 'studentNumber', label: 'Roll number' },
      { key: 'status', label: 'Status', render: (row) => statusBadge(row.status) },
      {
        key: 'matchScore',
        label: 'Match',
        align: 'right',
        render: (row) => (row.matchScore === null || row.matchScore === undefined
          ? '—'
          : formatPercentage(row.matchScore * 100, 0))
      },
      {
        key: 'method',
        label: 'Recorded by',
        render: (row) => h('span.badge', row.method === 'FACE_VERIFIED' ? 'Face check' : humanise(row.method))
      },
      {
        key: 'actions',
        label: '',
        render: (row) => h('div.roster-actions', [
          h('button.button.button-secondary.button-sm', {
            type: 'button',
            onClick: () => markAttendance(session.id, row)
          }, 'Mark')
        ])
      }
    ], { empty: 'Nobody is enrolled in this class yet.' });

    endButton.onclick = async () => {
      const confirmed = await confirmDialog({
        title: 'End this session?',
        message: 'Students will no longer be able to scan the code. Records that were already captured stay in place.',
        confirmLabel: 'End session',
        variant: 'danger'
      });
      if (!confirmed) {
        return;
      }      stop();
      endButton.disabled = true;
      try {
        await api.post(`/faculty/sessions/${session.id}/close`);
      } catch (failure) {
        toast(failure.message, 'error');
        endButton.disabled = false;
        return;
      }
      invalidate('/faculty/sessions');
      invalidate('/faculty/dashboard');
      toast('The session has been closed.', 'success');

      // The session is gone, so the screen says so. It does not fall back to an older
      // session, because that would look as if the code was still running.
      openSessions = openSessions.filter((entry) => entry.id !== session.id);
      showNothing();
      const fresh = await cachedGet('/faculty/sessions', { params: { status: 'ACTIVE' } });
      openSessions = fresh;
      paintLectures(lectures);
    };

    replaceChildren(sessionArea, h('div.stack.stack-5', [
      h('div.card', [
        h('div.card-header', [
          h('div', [
            h('h3', `${session.subjectCode} · ${session.section}`),
            h('p.text-sm.text-muted', `${session.room} · started ${formatDateTime(session.startedAt)}`)
          ]),
          h('div.row.gap-3', [liveState, endButton])
        ]),
        h('div.card-body.stack.stack-4', [
          qrHost,
          h('div.text-center', countdown),
          h('div.alert.alert-info', h('span',
            'Project this code on the screen. It is signed by the server, changes every second '
            + 'and stops working within seconds of being captured on a phone.'))
        ])
      ]),
      h('div.card', [
        h('div.card-header', [
          h('h3', 'Live roster'),
          h('span.badge', 'Updates every 3 seconds')
        ]),
        h('div.card-body card-body-flush', rosterHost)
      ])
    ]));

    refreshQr();
    refreshRoster();
    timer = window.setInterval(refreshQr, 1000);
    rosterTimer = window.setInterval(refreshRoster, 3000);

    // The server pushes the change, so the roster is up to date at once. The poll is
    // only the safety net for a connection that broke.
    const stopWatching = watchAccount(currentAccount()?.id);
    const stopListening = onChange('roster', () => {
      refreshRoster();
    });
    const stopSessions = onChange('sessions', () => {
      invalidate('/faculty/sessions');
    });

    // The router calls this when the teacher leaves the screen, so the polling stops.
    onDispose(container, () => {
      stop();
      stopWatching();
      stopListening();
      stopSessions();
    });
  }
}

/**
 * Opens the correction dialog for one student.
 */
async function markAttendance(sessionId, student) {
  const reasonSelect = h('select.select', [
    h('option', { value: 'VERIFIED_ROLL_CALL' }, 'Verified during roll call'),
    h('option', { value: 'TECHNICAL_ISSUE' }, 'Technical issue with the camera or code'),
    h('option', { value: 'MEDICAL' }, 'Medical reason'),
    h('option', { value: 'LATE_ARRIVAL' }, 'Late arrival, admitted'),
    h('option', { value: 'CORRECTION' }, 'Other correction')
  ]);

  const statusSelect = h('select.select', [
    h('option', { value: 'PRESENT' }, 'Present'),
    h('option', { value: 'ABSENT' }, 'Absent'),
    h('option', { value: 'EXCUSED' }, 'Excused')
  ]);

  const explanation = h('textarea.textarea', {
    rows: 3,
    maxLength: 500,
    placeholder: 'Explain why this record is being changed. It is written to the audit trail.'
  });

  const outcome = await openModal({
    title: `Mark ${student.studentName}`,
    body: () => h('div.stack.stack-4', [
      h('p.text-sm.text-muted', `${student.studentNumber || 'No roll number'} · currently ${humanise(student.status)}`),
      h('div.field', [h('label', 'New status'), statusSelect]),
      h('div.field', [h('label', 'Reason'), reasonSelect]),
      h('div.field', [h('label', 'Explanation'), explanation])
    ]),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(undefined) }, 'Cancel'),
      (ctx) => h('button.button.button-primary', {
        type: 'button',
        onClick: () => ctx.close({
          status: statusSelect.value,
          reasonCode: reasonSelect.value,
          explanation: explanation.value.trim()
        })
      }, 'Save the change')
    ]
  });

  if (!outcome) {
    return;
  }
  if (!outcome.explanation) {
    toast('Please explain why the record is being changed.', 'error');
    return;
  }

  try {
    await api.patch(`/faculty/sessions/${sessionId}/roster/${student.studentId}`, outcome);
    invalidate('/faculty/dashboard');
    toast('The record was updated and written to the audit trail.', 'success');
  } catch (failure) {
    toast(failure.message, 'error');
  }
}

/* -------------------------------------------------------------------------- */
/* Timetable                                                                   */
/* -------------------------------------------------------------------------- */

const WEEKDAYS = [
  { value: 1, label: 'Monday' },
  { value: 2, label: 'Tuesday' },
  { value: 3, label: 'Wednesday' },
  { value: 4, label: 'Thursday' },
  { value: 5, label: 'Friday' },
  { value: 6, label: 'Saturday' },
  { value: 7, label: 'Sunday' }
];

/**
 * Renders the teaching diary, where the teacher schedules their own lectures.
 */
export async function renderFacultyTimetable(container, route) {
  const classes = await cachedGet('/faculty/classes');

  const paint = (slots) => {
    if (!isCurrent(container, route)) {
      return;
    }

    const scheduler = h('div.card', createScheduler({
      slots,
      editable: true,
      onSlotClick: (lecture) => openSlotEditor(container, classes, slots, lecture),
      onEmptyClick: (position) => openSlotEditor(container, classes, slots, null, position)
    }));

    container.replaceChildren(h('div.stack.stack-5', [
      h('div.page-head', [
        h('div', [
          h('h1', 'My timetable'),
          h('p.lead', 'Schedule your lectures across the week. Only the subjects you teach can be added.')
        ]),
        h('button.button.button-primary', {
          type: 'button',
          onClick: () => openSlotEditor(container, classes, slots, null)
        }, 'Add a lecture')
      ]),
      scheduler,
      h('div.card', [
        h('div.card-header', [h('h3', 'As a list')]),
        h('div.card-body card-body-flush', slotList(slots))
      ])
    ]));
  };

  const slots = await cachedGet('/faculty/timetable', {
    onRefresh: (fresh) => paint(fresh)
  });
  paint(slots);
}

/**
 * A plain list of the lectures, for small screens and for printing.
 */
function slotList(slots) {
  if (!slots || slots.length === 0) {
    return emptyState('You have not scheduled any lectures yet.', 'calendar');
  }
  const byDay = new Map();
  for (const slot of slots) {
    if (!byDay.has(slot.dayOfWeek)) {
      byDay.set(slot.dayOfWeek, []);
    }
    byDay.get(slot.dayOfWeek).push(slot);
  }
  return h('div', [...byDay.entries()]
    .sort((left, right) => left[0] - right[0])
    .map(([day, daySlots]) => h('section.day-group', [
      h('header', [
        h('span', WEEKDAYS.find((entry) => entry.value === day)?.label || ''),
        h('span', `${daySlots.length} ${daySlots.length === 1 ? 'lecture' : 'lectures'}`)
      ]),
      daySlots.map((slot) => h('div.slot', [
        h('div.time', `${slot.starts} – ${slot.ends}`),
        h('div', [
          h('div.subject', `${slot.subjectCode} · ${slot.subjectName}`),
          h('div.meta', `${slot.section}${slot.room ? ` · ${slot.room}` : ''}`)
        ]),
        h('span.badge', slot.room || '')
      ]))
    ])));
}

/**
 * Opens the editor for a lecture. When there is no lecture yet it creates one.
 */
async function openSlotEditor(container, classes, slots, lecture, position) {
  const isNew = !lecture;
  const classSelect = h('select.select', classes.map((entry) => h('option', {
    value: String(entry.id),
    selected: lecture && entry.id === lecture.classId
  }, `${entry.subjectCode} · ${entry.section} · ${entry.room}`)));

  const daySelect = h('select.select', WEEKDAYS.map((entry) => h('option', {
    value: String(entry.value),
    selected: lecture ? entry.value === lecture.dayOfWeek : entry.value === (position?.dayOfWeek ?? 1)
  }, entry.label)));

  const startInput = h('input.input', {
    type: 'time',
    step: '300',
    value: lecture ? lecture.starts : (position?.starts ?? '09:00')
  });

  const endInput = h('input.input', {
    type: 'time',
    step: '300',
    value: lecture ? lecture.ends : '10:00'
  });

  const error = h('div');

  const outcome = await openModal({
    title: isNew ? 'Schedule a lecture' : 'Change the lecture',
    body: () => h('div.stack.stack-4', [
      error,
      h('div.field', [h('label', 'Subject and section'), classSelect]),
      h('div.field', [h('label', 'Day'), daySelect]),
      h('div.row.gap-3', [
        h('div.field.grow', [h('label', 'Starts'), startInput]),
        h('div.field.grow', [h('label', 'Ends'), endInput])
      ]),
      h('p.text-xs.text-subtle', 'A lecture cannot overlap another one of yours, and a class cannot be in two rooms at once.')
    ]),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(undefined) }, 'Cancel'),
      (ctx) => h('button.button.button-primary', {
        type: 'button',
        onClick: () => ctx.close({
          classId: Number(classSelect.value),
          dayOfWeek: Number(daySelect.value),
          starts: startInput.value,
          ends: endInput.value
        })
      }, isNew ? 'Add the lecture' : 'Save the change')
    ]
  });

  if (!outcome) {
    return;
  }

  try {
    if (isNew) {
      await api.post('/faculty/timetable', outcome);
      toast('The lecture was added to your timetable.', 'success');
    } else {
      await api.patch(`/faculty/timetable/${lecture.id}`, outcome);
      toast('The lecture was updated.', 'success');
    }
  } catch (failure) {
    toast(failure.message, 'error');
    return;
  }

  invalidate('/faculty/timetable');
  invalidate('/faculty/dashboard');
  // The route is read from the container, so the screen knows it is still the current
  // one and paints the lecture that was just added.
  await renderFacultyTimetable(container, container.dataset.route);
}

/* -------------------------------------------------------------------------- */
/* Reports                                                                     */
/* -------------------------------------------------------------------------- */

/**
 * Renders the attendance reports of the teacher.
 */
export async function renderFacultyReports(container, route) {
  const [subjects, low] = await Promise.all([
    api.get('/faculty/reports/subjects'),
    api.get('/faculty/reports/low-attendance')
  ]);
  if (!isCurrent(container, route)) {
    return;
  }

  const subjectTable = table(subjects, [
    { key: 'subjectCode', label: 'Code' },
    { key: 'subjectName', label: 'Subject' },
    { key: 'sessions', label: 'Held', align: 'right' },
    { key: 'attended', label: 'Present', align: 'right' },
    { key: 'percentage', label: 'Rate', align: 'right', render: (row) => formatPercentage(row.percentage) }
  ], { empty: 'No sessions have been taken yet.' });

  const lowTable = table(low, [
    { key: 'studentNumber', label: 'Roll number' },
    { key: 'studentName', label: 'Student' },
    { key: 'subjectCode', label: 'Subject' },
    { key: 'sessions', label: 'Held', align: 'right' },
    { key: 'attended', label: 'Present', align: 'right' },
    { key: 'percentage', label: 'Rate', align: 'right', render: (row) => formatPercentage(row.percentage) }
  ], { empty: 'Every student is above the threshold.' });

  container.replaceChildren(h('div.stack.stack-6', [
    h('div.page-head', [
      h('div', [
        h('h1', 'Reports'),
        h('p.lead', 'Totals computed from the attendance records.')
      ]),
      h('div.row.gap-2', [
        h('a.button.button-secondary', { href: '/api/faculty/reports/low-attendance.csv', download: '' }, 'CSV'),
        h('a.button.button-secondary', { href: '/api/faculty/reports/low-attendance.pdf', download: '' }, 'PDF')
      ])
    ]),
    h('div.card', [
      h('div.card-header', [h('h3', 'By subject')]),
      h('div.card-body card-body-flush', subjectTable)
    ]),
    h('div.card', [
      h('div.card-header', [h('h3', 'Below the threshold')]),
      h('div.card-body card-body-flush', lowTable)
    ])
  ]));
}
