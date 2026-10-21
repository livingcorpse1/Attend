-- =====================================================================================
-- Moves the institution details out of the schema.
--
-- The name and the location of the college were compiled into the migration. They are
-- facts about the institution, not about the software, so they are removed here and
-- set by an administrator from the administration screens.
-- =====================================================================================
delete from app_setting where setting_key in ('institution_name', 'institution_location');
