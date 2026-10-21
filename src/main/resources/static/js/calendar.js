/**
 * The fortnight calendar and the week scheduler.
 *
 * Both are built from the same weekly lecture pattern. The timetable in the database is
 * recurring, so a lecture that sits on a Wednesday is on every Wednesday until it is
 * moved. The calendar shows that pattern across two weeks; the scheduler shows one week
 * as a diary that can be edited.
 */

import { h } from './dom.js';
import { formatRange, formatTime, weekdayName, weekdayShort } from './ui.js';

/** The first day of the week, Monday. */
const MONDAY = 1;
const SUNDAY = 7;

/**
 * The Monday of the week that contains the given date.
 */
export function mondayOf(date) {
  const copy = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  const weekday = copy.getDay() === 0 ? 7 : copy.getDay();
  copy.setDate(copy.getDate() - (weekday - MONDAY));
  return copy;
}

/**
 * Adds days to a date.
 */
export function addDays(date, days) {
  const copy = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  copy.setDate(copy.getDate() + days);
  return copy;
}

/**
 * The date of a weekday in a given week, where the week starts on a Monday.
 */
function dateOfWeekday(weekStart, dayOfWeek) {
  return addDays(weekStart, dayOfWeek - MONDAY);
}

/**
 * Parses a "HH:mm" string into minutes after midnight.
 */
export function toMinutes(value) {
  const [hours, minutes] = String(value).split(':');
  return (Number(hours) || 0) * 60 + (Number(minutes) || 0);
}

/**
 * Formats minutes after midnight back into "HH:mm".
 */
export function fromMinutes(total) {
  const hours = Math.floor(total / 60);
  const minutes = total % 60;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
}

/**
 * The lectures of one weekday, ordered by start time.
 */
export function lecturesOn(slots, dayOfWeek) {
  return (slots || [])
    .filter((slot) => Number(slot.dayOfWeek) === dayOfWeek)
    .sort((left, right) => toMinutes(left.starts) - toMinutes(right.starts));
}

/**
 * Builds the fortnight calendar.
 *
 * <p>Two rows of seven days. The rows follow each other week by week, so the arrows
 * move a whole week and the teacher always sees what is coming.
 *
 * @param {object} options
 * @param {Array} options.slots The lectures of the recurring weekly pattern.
 * @param {Function} [options.onSlotClick] Called with a lecture when one is clicked.
 * @returns {HTMLElement}
 */
export function createCalendar({ slots, onSlotClick }) {
  let weekStart = mondayOf(new Date());
  const root = h('div');

  const label = h('h3');
  const grid = h('div.calendar');

  const paint = () => {
    const first = weekStart;
    const last = addDays(weekStart, 13);
    const sameMonth = first.getMonth() === last.getMonth();
    label.textContent = sameMonth
      ? `${first.toLocaleDateString(undefined, { month: 'long' })} ${first.getDate()} – ${last.getDate()}, ${last.getFullYear()}`
      : `${first.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} – ${last.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}`;

    // The grid is emptied first. Without this every arrow click would leave the
    // previous fortnight behind and the rows would pile up.
    grid.replaceChildren();

    const today = new Date();
    for (let day = MONDAY; day <= SUNDAY; day += 1) {
      grid.append(h('div.calendar-head', weekdayShort(day)));
    }

    for (let week = 0; week < 2; week += 1) {
      for (let day = MONDAY; day <= SUNDAY; day += 1) {
        const date = dateOfWeekday(addDays(weekStart, week * 7), day);
        const isToday = date.getTime() === new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
        const dayLectures = lecturesOn(slots, day);

        const cell = h(`div.calendar-day${isToday ? '.is-today' : ''}`);
        cell.append(h('div.calendar-date', [
          h('span', String(date.getDate())),
          isToday ? h('span.today-mark') : null
        ]));

        dayLectures.slice(0, 3).forEach((lecture) => {
          const chip = h('button.calendar-chip', {
            type: 'button',
            title: `${lecture.subjectCode} ${lecture.subjectName} · ${lecture.room} · ${formatRange(lecture.starts, lecture.ends)}`,
            onClick: () => onSlotClick?.(lecture)
          }, [
            h('span.time', formatRange(lecture.starts, lecture.ends)),
            h('span.subject', lecture.subjectCode),
            h('span.room', lecture.room)
          ]);
          cell.append(chip);
        });

        if (dayLectures.length > 3) {
          cell.append(h('div.calendar-more', `+${dayLectures.length - 3} more`));
        }
        grid.append(cell);
      }
    }
  };

  const previous = h('button.icon-button', {
    type: 'button',
    'aria-label': 'Previous week',
    onClick: () => {
      weekStart = addDays(weekStart, -7);
      paint();
    }
  }, '‹');

  const next = h('button.icon-button', {
    type: 'button',
    'aria-label': 'Next week',
    onClick: () => {
      weekStart = addDays(weekStart, 7);
      paint();
    }
  }, '›');

  const today = h('button.button.button-secondary.button-sm', {
    type: 'button',
    onClick: () => {
      weekStart = mondayOf(new Date());
      paint();
    }
  }, 'Today');

  paint();

  root.append(
    h('div.card-header', [
      h('div', [label, h('p.text-xs.text-subtle', { style: { marginTop: '2px' } },
        'Your teaching week, repeated across the next fortnight')]),
      h('div.row.gap-2', [today, previous, next])
    ]),
    grid
  );
  return root;
}

/**
 * Builds the week scheduler.
 *
 * <p>The grid is one row per hour and one column per weekday. A lecture is placed in
 * its column at the height that matches its time. Clicking an empty column opens the
 * editor for that day and time; clicking a lecture opens it for editing.
 *
 * @param {object} options
 * @param {Array} options.slots The lectures.
 * @param {boolean} [options.editable] Whether lectures can be created and moved.
 * @param {Function} [options.onSlotClick] Called with a lecture.
 * @param {Function} [options.onEmptyClick] Called with the day and the snapped time.
 * @returns {HTMLElement}
 */
export function createScheduler({ slots, editable = false, onSlotClick, onEmptyClick }) {
  const all = slots || [];
  const earliest = all.length > 0 ? Math.min(...all.map((slot) => toMinutes(slot.starts))) : 9 * 60;
  const latest = all.length > 0 ? Math.max(...all.map((slot) => toMinutes(slot.ends))) : 17 * 60;

  const dayStart = Math.max(6 * 60, Math.floor((earliest - 30) / 60) * 60);
  const dayEnd = Math.min(23 * 60, Math.ceil((latest + 30) / 60) * 60);
  const hours = Math.max(1, Math.round((dayEnd - dayStart) / 60));
  const totalMinutes = hours * 60;

  const grid = h('div.scheduler');
  grid.style.gridTemplateRows = `auto repeat(${hours}, 58px)`;

  grid.append(h('div.scheduler-head', { style: { gridColumn: '1' } }, ''));

  const today = new Date();
  for (let day = MONDAY; day <= SUNDAY; day += 1) {
    const date = dateOfWeekday(mondayOf(today), day);
    const isToday = date.getTime() === today.getTime()
      || (today.getDay() === 0 && day === SUNDAY && date.getTime() === today.getTime());
    grid.append(h(`div.scheduler-head${isToday ? '.is-today' : ''}`, {
      style: { gridColumn: String(day + 1) }
    }, [
      h('span', weekdayShort(day)),
      h('span.date', String(date.getDate()))
    ]));
  }

  for (let hour = 0; hour < hours; hour += 1) {
    const minutes = dayStart + hour * 60;
    grid.append(h('div.scheduler-hour', {
      style: { gridRow: String(hour + 2), gridColumn: '1' }
    }, fromMinutes(minutes)));
  }

  for (let day = MONDAY; day <= SUNDAY; day += 1) {
    const column = h('div.scheduler-cell', {
      style: { gridRow: `2 / span ${hours}`, gridColumn: String(day + 1) }
    });

    if (editable) {
      column.addEventListener('click', (event) => {
        if (event.target !== column) {
          return;
        }
        const bounds = column.getBoundingClientRect();
        const ratio = (event.clientY - bounds.top) / bounds.height;
        const raw = dayStart + ratio * totalMinutes;
        const snapped = Math.round(raw / 30) * 30;
        const start = Math.max(dayStart, Math.min(dayEnd - 30, snapped));
        onEmptyClick?.({ dayOfWeek: day, starts: fromMinutes(start) });
      });
    }

    lecturesOn(all, day).forEach((lecture) => {
      const start = toMinutes(lecture.starts);
      const end = toMinutes(lecture.ends);
      const offset = ((start - dayStart) / totalMinutes) * 100;
      const height = ((end - start) / totalMinutes) * 100;
      // A short block only has room for a line or two, so the detail is left out
      // rather than cut in half.
      const pixels = (height / 100) * hours * 58;
      const lines = [];
      if (pixels >= 34) {
        lines.push(h('span.time', formatRange(lecture.starts, lecture.ends)));
      }
      lines.push(h('span.subject', lecture.subjectCode));
      if (pixels >= 62) {
        lines.push(h('span.room', lecture.room));
      }
      const block = h('button.scheduler-slot', {
        type: 'button',
        style: { top: `${offset}%`, height: `calc(${height}% - 4px)` },
        title: `${lecture.subjectCode} · ${lecture.subjectName} · ${lecture.room} · ${formatRange(lecture.starts, lecture.ends)}`,
        onClick: () => onSlotClick?.(lecture)
      }, lines);
      column.append(block);
    });

    grid.append(column);
  }

  const legend = h('div.scheduler-legend', [
    h('span', [h('span.swatch', { style: { background: 'var(--brand)' } }), 'Your lectures']),
    editable ? h('span', 'Click an empty column to add a lecture. Click a lecture to change it.') : null
  ]);

  return h('div', [grid, legend]);
}
