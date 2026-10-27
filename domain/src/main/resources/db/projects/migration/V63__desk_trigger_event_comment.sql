-- DESK-MENTIONED ROWS NAME THEIR COMMENT (qits-1660).
--
-- A desk-mentioned row now carries the comment it came from, so a person on the Desk page can open
-- that exact comment. Rows of the other sources keep comment_id null.
--
-- Each comment is its own row: the unique key gains comment_id. coalesce keeps a null comment_id
-- equal to another null one, so for the other sources a repeat is still one line, as V57 had it.
-- The message the desk gets still shows one line per item and author (DeskMentionedSource.render).
alter table desk_trigger_event add column comment_id text;
alter table desk_trigger_event drop constraint desk_trigger_event_window_id_source_item_ref_label_key;
create unique index desk_trigger_event_one_line
  on desk_trigger_event (window_id, source, item_ref, label, coalesce(comment_id, ''));
