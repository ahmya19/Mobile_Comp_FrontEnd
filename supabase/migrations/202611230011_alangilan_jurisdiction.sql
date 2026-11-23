begin;
-- Approved initial jurisdiction (November 23, 2026): Alangilan, Batangas City. SMS reports are routed ONLY to approved
-- barangays in this table, so this is the explicit service-area configuration; add rows here to extend it deliberately.
-- The centroid is APPROXIMATE barangay-level data (PhilAtlas, Alangilan, City of Batangas: 13.7819 N, 121.0671 E), not a
-- device GPS fix; the SMS report's location text already says "Barangay center (SMS location)".
-- Only the three exact simulation rows from the SMS test configuration are removed. No report, history or receipt is
-- rewritten or matched by name.
insert into private.barangays(name,latitude,longitude) values('Alangilan',13.7819,121.0671)
  on conflict(name) do update set latitude=excluded.latitude,longitude=excluded.longitude;
delete from private.barangays where name in ('Test Barangay A','Test Barangay B','Test Barangay C');
commit;
