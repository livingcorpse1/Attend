-- =====================================================================================
-- The college was renamed.
--
-- The name of the institution is a setting, so the row that carries the old name is
-- replaced here. The environment keeps the default for a fresh installation.
--
-- The statement is written in plain SQL, so it runs on PostgreSQL and on the H2
-- instance of the tests alike.
-- =====================================================================================
update app_setting
   set setting_value = 'Apsit college'
 where setting_key = 'institution_name';

insert into app_setting (setting_key, setting_value)
select 'institution_name', 'Apsit college'
 where not exists (select 1 from app_setting where setting_key = 'institution_name');
