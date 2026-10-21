-- =====================================================================================
-- The administrator is gone.
--
-- The college is run by the people who use it. There are only two roles now, a teacher
-- and a student, and the teacher owns the academic structure of their own subjects.
--
-- The check constraint on the role is narrowed, and the administrator account that the
-- first versions created is removed. The entries that administrator wrote go with it,
-- because the trail is only meaningful while the account exists.
-- =====================================================================================
delete from audit_log
 where actor_id in (select id from app_user where role = 'ADMIN');

delete from app_user where role = 'ADMIN';

alter table app_user drop constraint if exists ck_app_user_role;
alter table app_user add constraint ck_app_user_role check (role in ('FACULTY', 'STUDENT'));
