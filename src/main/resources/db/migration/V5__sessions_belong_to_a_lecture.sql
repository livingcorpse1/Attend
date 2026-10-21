-- =====================================================================================
-- An attendance session belongs to a scheduled lecture.
--
-- A teacher takes the attendance of a lecture that is on their timetable. The session
-- records which lecture it was, so the record cannot drift away from the schedule.
-- =====================================================================================
alter table attendance_session
  add column timetable_slot_id bigint references timetable(id);

create index ix_session_slot on attendance_session(timetable_slot_id);
