-- A RELEASE REQUEST NO LONGER STORES WHO OPENED IT OR A SUMMARY OF ITSELF (qits-1673, contract step).
--
-- requester named the principal of the ask that opened a request; who took part is
-- release_request_participant (V66), written on every ask and never deleted. summary held the
-- request's items' labels joined — derived from its sources since the expand step, and stored only
-- for the readers that still read the column. Every reader now derives the text from the sources
-- at the moment it needs it (ReleaseRequestItems): the fold's merge message, the release commit and
-- its tag, and the desk's approval line. Nothing writes or reads either column any more.
alter table release_request drop column requester;
alter table release_request drop column summary;
