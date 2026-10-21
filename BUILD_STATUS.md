# Build status

Last reviewed: 2026-10-22, against version 2.0.0.

## Completed

### Backend

* Spring Boot 3.5 on Java 21, restructured into `config`, `domain`, `repository`,
  `security`, `service` and `web` packages.
* Flyway managed schema with the constraints that protect an attendance decision in the
  database rather than only in the service layer.
* Supabase Postgres through plain JDBC, plus a storage abstraction with a Supabase
  Storage implementation and a local provider for development.
* Session authentication with BCrypt, CSRF double submit tokens, a content security
  policy that forbids third party origins and a login throttle stored in the database.
* Self registration for students with a versioned consent notice and a password policy.
* A mandatory identity check: a random liveness challenge, server side detection,
  alignment, embedding and comparison, with the template stored as a descriptor.
* Rotating QR tokens signed with HMAC-SHA-256, bound to the session nonce, valid for
  eight seconds and compared in constant time.
* The attendance journey: scan, ticket, face check, record, with single use tickets and
  a submission interval.
* A teaching diary a teacher owns: lectures can be added, moved and removed, and the
  server refuses a slot that would double book the teacher or the class.
* Attendance follows the diary. A session belongs to a lecture, the server refuses a
  session for a lecture of another teacher or for a lecture that is not running, and the
  screen lists the lectures of the day.
* The teacher owns the academic structure: subjects, class sections and enrolments, all
  scoped to the teacher, all written to the audit trail.
* CSV and PDF exports for the teacher. The student has no export.
* Two pairs of face models, chosen by a setting: the permissive YuNet and SFace, and the
  much stronger SCRFD and ArcFace of the InsightFace family. The pair is part of the
  version that is stored with every template.
* Scheduled housekeeping that closes expired sessions and prunes the temporary tables.

### Roles

There are only two. The college is run by the people who use it, so there is no
administrator, no administration console and no administrator account.

* A teacher owns their own academic structure: the subjects they teach, the class
  sections that run them, the students who attend and the lectures they schedule.
  Nobody can touch anybody else's, and every change is written to the audit trail.
* A teacher adds the other teachers, because there is nobody else who could. The
  generated password is returned once.
* A student registers themselves and is then held at the identity check until the face
  template is enrolled.
* The institution details are configuration, read from the environment, and the
  attendance threshold and the version of the biometric notice are settings in the
  database.

### Nothing about the college is compiled in

The only seeded data is the demonstration: a staff of five teachers, one subject each
with its class section, its students and its weekly lecture, and four students.

* The name and the location of the college come from the environment, so the same build
  can be pointed at any college.
* The subjects, the class sections, the rooms and the schedule are created by the
  teacher who owns them, and can be changed or removed at any time.
* The version of the biometric notice is read from the database, so the client is told
  which version to send and the server decides whether it is current.
* The hours of the teaching day are configuration, not a constant in the service.
* The tests build the structure they need instead of relying on seeded rows.
* The browser smoke test and the screenshot script build the structure they need through
  the teacher's own API and remove it again, so a run leaves no trace.

### Speed

The database is a few hundred milliseconds away, so the shape of the queries matters.

* The dashboard sends its four independent reads at the same time instead of one after
  the other. That alone took the screen from 3.2 seconds to 0.7.
* The list of sessions reads the classes and the present counts once for the whole list
  instead of once per session. That was the last N+1 and it cost 2.4 seconds.
* The client keeps the last answer for every screen, refreshes it in the background and
  shows it straight away, and warms a screen when the pointer rests on it. The teacher
  and the student screens both do this.
* The screens that change the data, starting a session or marking attendance, drop the
  affected entries from the cache so the next read is fresh.
* The connection pool is opened while the application starts, so the first person to
  open the dashboard does not pay for the handshake.

### The identity check hands over

Finishing the identity check changes the account. The client reads the account again
before it moves on, otherwise the route guard still sees the pending state and sends the
student straight back to the check. There is a test for it.

### Surviving the pooler

A managed database closes connections that have been idle for a while. A pool that
hands those dead connections to a request makes every query fail, and the symptom is a
login screen that says "something went wrong" with nothing to go on.

* The pool pings its idle connections every 30 seconds, so a dropped one is found and
  replaced before anybody borrows it. The pool silently switches a keepalive under
  30 seconds off, so the value is pinned by a test.
* A connection is retired after five minutes, long before the server would drop it, and
  unused ones are dropped after a minute.
* Only one connection is kept idle, because every extra one is another that can go
  stale.
* A request waits 30 seconds for a replacement connection rather than failing after
  ten.
* A database that cannot be reached is reported as `503 DATABASE_UNAVAILABLE` with a
  sentence a person can read, not as a 500 with a reference number.
* `/api/health` now reports the database, so the condition is visible before anybody
  tries to sign in.

`scripts/soak-database.ps1` leaves the server alone for a while and then checks that
the database is still reachable and that a sign in still works.

### Client

* A PWA with a manifest, a service worker, generated icons and an offline screen.
* Native ES modules with no build step and no third party origin.
* A design token based stylesheet: warm neutrals, one evergreen accent, no blue and no
  purple, flat buttons, hairline borders, a light and a dark theme.
* A hyperscript helper, so text from the server is never parsed as markup.
* A fortnight calendar, two rows of seven days, on the faculty dashboard.
* A week scheduler on the faculty timetable: days across, hours down, lectures placed by
  time, with an editor for creating, moving and removing a lecture.
* Screens for the teacher (dashboard, live attendance, timetable, reports, profile) and
  for the student (dashboard, verify presence, attendance, timetable, profile), plus the
  sign in and sign up screens. The identity check lives in the profile of the student.
* The attendance screen lists the lectures of the day and starts the one that is running.
* The camera of the student is visible while the classroom code is scanned, and a button
  releases it. The code cannot be typed in by hand.
* A read cache with a stale while revalidate refresh, and a prefetch when the pointer
  rests on a menu entry, so the screens open without a wait.
* A route stamp on the view container, so a screen that finishes loading after the user
  has already moved on cannot overwrite the newer one.

### Tooling

* `scripts/fetch-models.ps1` and `scripts/fetch-models.sh` download the ONNX models of
  the chosen pair and verify their SHA-256. `-Profile Strong` installs the pair of the
  InsightFace family.
* `scripts/generate-icons.ps1` draws the application icons.
* `scripts/BuildIcons.java` builds the icons and the favicon from the picture of the
  brand, so the mark in the menu and the icon of the browser are the same.
* `scripts/check-client.mjs` parses every client module.
* `scripts/smoke-client.mjs` drives a headless browser through every screen.
* `scripts/verify-features.mjs` drives the camera with a fake device and checks the
  identity check, the code scanner, the menus and the live refresh.
* `scripts/FakeCamera.java`, `scripts/TurnFace.java` and `scripts/qr-picture.mjs` build
  the video that the fake device plays.
* `scripts/check-supabase.ps1` probes the configured Supabase project and prints the
  schema it finds, without writing anything and without printing a secret.
* `scripts/query.ps1` runs a statement that reads against the configured database.
* A local `.env` file is read on start. It has the lowest priority, so a real
  environment variable always wins over it.

## Verified

* The Maven suite passes: 59 tests.
  * `FaceRecognitionServiceTest` runs the real models against a known portrait: one
    face detected, the alignment produces the expected crop, the descriptor is stable
    and two different faces score below the match threshold. Every pair of models that
    is installed goes through the same checks, and the two detectors have to agree on
    where the eyes of the face are.
  * `QrTokenServiceTest` covers signing, expiry, tampering and a foreign secret.
  * `AttendanceFlowIntegrationTest` covers the whole journey: the gate before the
    identity check, registration and consent, the password policy, the CSRF
    requirement, role scoping, the single use ticket, a failed face check creating no
    record, a tampered code and the audited teacher correction. It also covers the
    rule that attendance is only taken for a lecture of the day and of that teacher.
  * `TimetableIntegrationTest` covers the teaching diary: a teacher sees only their own
    slots, can add a lecture to their own subject, cannot schedule a subject they do
    not teach, cannot overlap themselves or the class, cannot leave the teaching day,
    and cannot touch the lecture of another teacher.
  * `DatabasePoolConfigurationTest` pins the pool settings that keep the application
    alive against a pooler that closes idle connections, and checks that an unknown
    address or a wrong method is not reported as an internal error.
* The browser smoke test passes 23 checks over the sign in, sign up, teacher and student
  screens with no console errors. It builds the subject, the class section, the lecture
  and the enrolment it needs through the teacher's own API, because nothing is seeded.
* The browser check `scripts/verify-features.mjs` passes 24 checks, with the camera
  driven by a fake device that plays a real portrait and a real classroom code:
  * the session survives a reload of the page, and it survives a restart of the server,
    because the sessions live in the database;
  * every entry of the menu opens its screen, and the screen that marks the attendance
    is closed until the identity check is done;
  * the camera of the identity check shows a picture, the preview is mirrored and the
    frames that are sent to the server are not;
  * the server accepts a lit portrait: the exposure is judged on the face and not on the
    whole frame, which is what a portrait with a dark background used to trip over;
  * the scanner reads the code of the classroom out of the picture of the camera;
  * a lecture that is added to the diary shows up at once, and every lecture in the week
    grid sits on the line of its hour;
  * the dashboard keeps itself up to date, without a refresh.
* The application starts against the local H2 instance, applies the migrations and
  answers the health probe. It also runs against Supabase, where the schema was applied
  and the private storage bucket was created through the Storage API.
* A ten minute soak against Supabase passed with no failures and no connection
  warnings in the log.

## Not done, on purpose

* No passive presentation attack detection. See the limits in [SECURITY.md](SECURITY.md).
* No second factor for the sign in.
* No timetable conflict detection and no automatic session start from the timetable.
* No push notifications, no parent or guardian view and no absentee alert.
* No bulk import of the student list from a spreadsheet.
* The administration screens manage accounts, classes and enrolments; they do not yet
  build the timetable graphically.
* The audit trail is read only in the application. It is append only, but an operator
  with database access can still delete rows.
* The name of the college is a setting in the database, and there is no screen to change
  it any more, because the administration is gone. A migration or a statement against
  the database changes it.
* The liveness check is verified with the unit tests and with a fake camera that plays
  a face. A fake camera cannot really turn a head, so the movement itself was not
  measured end to end in a browser.

## Known limits of this build

* The identity columns move when a transaction is rolled back, so tests read the
  identifiers from the data instead of hard coding them.
* The client has no unit tests. The browser smoke test covers the screens, but the
  rendering helpers are not unit tested.
* The headless browser smoke test needs a Chromium based browser and Node with the
  `--experimental-websocket` flag. It is not part of the Maven build.
