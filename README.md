# VeriQra

**Presence, verified.** A college attendance platform where a rotating, signed classroom
QR code is combined with a face check that runs entirely on the server.

Built for **APSHAH College, Anandnagar, Thane**.

---

## What it does

A teacher opens an attendance session for a lecture of their teaching diary and projects
a code that changes every second. A student scans the code with the installed web app and
is asked to perform a random head movement. The server then decides three things on its
own:

1. **Was the code genuine?** The payload is signed with HMAC-SHA-256, expires within
   seconds and is bound to the session, so a screenshot of the screen is worthless.
2. **Was a live person in front of the camera?** The server picks the movement and
   checks the head pose across the captured frames. A photograph cannot follow an
   instruction.
3. **Was it the enrolled student?** The live descriptor is compared with the template
   that was stored during the identity check.

Only when all three answers are yes does an attendance record appear. The browser is
never trusted: it sends pixels and nothing else.

### Roles

There are only two. The college is run by the people who use it, so there is no
administrator and no administration console.

| Role | What they get |
| --- | --- |
| **Teacher** | Dashboard with a fortnight calendar, live attendance with the rotating code and the live roster, their own subjects and class sections, a weekly scheduler for their lectures, reports and a profile. |
| **Student** | Sign up, complete the identity check, mark presence, see their own attendance, their timetable and their profile. |

A teacher owns their own academic structure: they create the subjects they teach, the
class sections that run them and the students who attend, and they can add a colleague to
the teaching staff. Nobody can touch anybody else's subjects, classes or lectures, and
every change is written to the audit trail.

### The profile

Both roles have a profile, and it is the one place that holds the account. The name at
the bottom of the menu is the way into it, and the sign out lives there as well.

The student finds the account details, the identity check with the camera, and the
biometric data that is stored about them. The teacher finds the account details and the
subjects and class sections they teach.

A student whose identity check is still open keeps the rest of the application: the
timetable, the attendance history and the classes that are taking attendance all stay
open, and only the two calls that mark an attendance are closed. The check is a step, not
a locked door.

The camera is mirrored in the preview, so the student sees themselves the way a
colleague sees them. The frames that are sent to the server are not mirrored, because a
mirrored face would not match the enrolment.

### Attendance follows the timetable

A teacher takes the attendance of a lecture, and only of a lecture that is on their
teaching diary and is running. The **Take attendance** screen lists the lectures of the
day, and the one that is running can be opened. A teacher with nothing on the timetable
has no attendance to take, and the server refuses a session that does not belong to a
lecture of the day. The window opens fifteen minutes before a lecture and closes two
hours after it ends, so a teacher who is a little late can still start it.

### The teaching diary

A teacher owns their own week. The **Timetable** screen is a scheduler: the days across
the top, the hours down the side, and each lecture placed at the height that matches its
time. Clicking an empty column opens the editor for that day and time, clicking a
lecture opens it for editing, and the server refuses anything that would double book
the teacher or the class.

The **Dashboard** carries the same diary as a 2x7 calendar, two weeks of seven days,
so a teacher can see the shape of their fortnight at a glance and step forward a week at
a time.

Only the subjects a teacher is assigned to can be scheduled, and only their own slots
can be read, changed or removed.

### Nothing about the college is compiled in

The name and the location of the college are configuration, read from the environment, so
the same build can be pointed at any college. Everything else is data:

- The **subjects**, the **class sections**, the **rooms** and the **weekly schedule** are
  created by the teacher who owns them, from the **My classes** screen. They live in the
  database and can be changed or removed at any time.
- The **version of the biometric notice** and the **attendance threshold** are institution
  settings in the database.
- The **accounts** are created by the students themselves. A teacher adds the other
  teachers, and the password is shown once.

The only seeded data is the demonstration: a staff of five teachers, one subject each
with its class section, its students and its weekly lecture, and four students. It exists
so the application can be tried out, and it can be switched off with
`DEMO_DATA_ENABLED=false`.

---

## The identity check

A student creates an account themselves. The account is created in a *pending* state: it
can sign in and use the parts of the application that only read, and it cannot mark an
attendance until the face check has passed.

1. The student accepts the biometric data notice (the version is recorded).
2. The server issues a random liveness challenge, for example *turn your head to your
   left*.
3. The browser captures five frames over about two seconds.
4. The server checks the frame quality, the head movement, that the frames show the same
   person, and that the descriptor matches the enrolment.
5. A 128 dimensional template is stored. The photographs are not part of the template.

The exposure of the face is judged on the face and not on the whole frame: the brightest
tenth of the crop has to reach a luminance of 45, and a portrait with a dark background
is not a dark portrait.

The student can withdraw the template at any time from their **profile**, which is also
where the identity check itself lives.

### The models

The pipeline runs on two pairs of models, and the pair is a setting.

| Pair | Detection | Recognition | Licence |
| --- | --- | --- | --- |
| `yunet-sface` (the default) | YuNet, five landmarks | SFace, 128 values | MIT and Apache-2.0 |
| `scrfd-arcface` (the strongest) | SCRFD, five landmarks | ArcFace ResNet50, 512 values | non commercial research |

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1            # the permissive pair
powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1 -Profile Strong
```

```bash
./scripts/fetch-models.sh            # the permissive pair
./scripts/fetch-models.sh --strong   # the strongest pair
```

The permissive pair is the default because it may be used for anything, including a
commercial deployment. The stronger pair finds far more faces in hard pictures and
separates identities much better, but the weights of the InsightFace family are
published for non commercial research, so that has to be settled before a deployment
that is not a college project uses them.

The pair is part of the version that is stored with every template. A template that was
enrolled with one pair is never compared with a descriptor of the other: the student is
asked to run the identity check again instead.

---

## Technology

| Part | Choice | Why |
| --- | --- | --- |
| Backend | Spring Boot 3.5, Java 21 | Session security, validation, a real HTTP layer. |
| Persistence | Plain JDBC on PostgreSQL | Supabase is PostgreSQL. Flyway owns the schema. |
| Database | Supabase Postgres + Supabase Storage | Managed, backed up, and the storage bucket keeps the enrolment images. |
| Computer vision | ONNX Runtime with YuNet and SFace | Detection and embedding run inside the trust boundary. No inference in the browser. |
| Client | Plain HTML, CSS and ES modules | No build step. The whole client is served from this origin. |
| Offline | A service worker | The application installs and opens on a weak connection. |

The client ships **no third party assets**. The QR libraries are vendored, the type
family is served from this origin and the content security policy forbids any external
origin.

### The look

The interface is **monochromatic**. There is no accent colour and no hue anywhere: six
greys carry the whole application — `#0D0D0D`, `#626262`, `#B5B5B5`, `#D0D0D0`,
`#EAEAEA` and `#F5F5F5` — and what tells one state from another is its weight and its
fill, not its colour.

The depth comes from a pair of soft shadows rather than from a colour: a surface is
either raised out of the page or pressed into it, with the light coming from the top left.
Primary actions are ink rather than a brand colour, which is what separates a tool from
a toy. A light and a dark theme are both first class, the type is one family in four
weights, and the motion is short and optional.

The whole system is written down in [DESIGN.md](DESIGN.md): the tokens, the shell, the
home page, the parts and the rules that hold them together. The icons are one drawing,
inline SVG on a 24 unit grid, and there is no unicode in place of an icon anywhere.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fetch-fonts.ps1
```

The fonts are downloaded once and served from this origin, so nothing is asked of a font
service at runtime.

### The home page

`/` is the first thing a person sees. A thin bar with the mark, the links to the
features, to the security and to the sign in. Then the headline, **Identity. Verified.**,
the sentence that explains it, and the two actions: **Get Started** and **Explore
Platform**. Then the features in three cards and the safeguards in a quiet list.

There is no picture on it. The type, the space and the grey do the work.

### The sign in

**Sign In** and **Get Started** open over the page as a dialog, so nobody is taken away
from what they were reading. The dialog holds the sign in and the sign up, and the two
swap places inside it. Signing out, from the profile or from the account menu, brings
the person back to the home page.

---

## Getting started

### Requirements

* Java 21 or newer
* Node 18 or newer, only for the optional client checks
* A Supabase project, or Docker for the local PostgreSQL

### 1. Install the models

The biometric pipeline needs two ONNX models. They are not in source control.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1
```

```bash
./scripts/fetch-models.sh
```

The script verifies the SHA-256 of each download. The models land in `models/`.

### 2. Configure the database

The application reads a local `.env` file automatically, and a real environment variable
always wins over the file. Copy the template and fill it in:

```powershell
Copy-Item .env.example .env
notepad .env
```

Create a Supabase project, then take the connection string from **Project Settings →
Database → Connection string**:

```
DATABASE_URL=jdbc:postgresql://postgres.PROJECT_REF:PASSWORD@aws-0-REGION.pooler.supabase.com:6543/postgres?sslmode=require
DATABASE_USER=postgres.PROJECT_REF
DATABASE_PASSWORD=your-database-password
```

Flyway creates the schema on the first start. No manual SQL is needed.

Check the connection before you start the application:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\check-supabase.ps1
```

For a local database without Supabase:

```powershell
docker compose up -d postgres
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/veriqra"
$env:DATABASE_USER = "veriqra"
$env:DATABASE_PASSWORD = "local-only-change-me"
$env:DATABASE_DRIVER = "org.postgresql.Driver"
```

### 3. Run

```powershell
.\mvnw.cmd spring-boot:run
```

Open <http://localhost:8080> and sign in with one of the demonstration accounts:

| Account | Password | What it shows |
| --- | --- | --- |
| `faculty@veriqra.local` | `Demo!2026` | The teacher of **AIML**, with a class section, its students and a weekly lecture. |
| `faculty2@veriqra.local` | `Demo!2026` | The teacher of **AIDS**. |
| `faculty3@veriqra.local` | `Demo!2026` | The teacher of **DBMS**. |
| `faculty4@veriqra.local` | `Demo!2026` | The teacher of **CN**. |
| `faculty5@veriqra.local` | `Demo!2026` | The teacher of **DSA**. |
| `student@veriqra.local` | `Demo!2026` | A student, enrolled in three of the classes. |
| `student2@veriqra.local` | `Demo!2026` | A student, enrolled in two of the classes. |
| `student3@veriqra.local` | `Demo!2026` | A student, enrolled in three of the classes. |
| `student4@veriqra.local` | `Demo!2026` | A student, enrolled in three of the classes. |

The demonstration accounts are the only seeded data. Every teacher owns one subject with
its class section, its students and its lecture of the week, so the dashboard, the
calendar and the live attendance screen all have something in them. They can be switched
off with `DEMO_DATA_ENABLED=false`.

Run with the `prod` profile to switch the demonstration accounts off as well.

### 4. Deploy

```powershell
.\mvnw.cmd clean package
docker compose up --build
```

The image contains the models and runs as an unprivileged user. Put it behind a TLS
terminating reverse proxy: the camera and the service worker both require HTTPS outside
localhost.

---

## Tests

```powershell
.\mvnw.cmd test
```

The suite covers the QR token rules, the whole attendance journey and the real computer
vision pipeline. The vision tests are skipped when the models are not installed.

The sessions of the tests are kept in the memory of the process, so a request can carry a
plain session object. A deployment keeps them in the database, so a restart of the server
does not sign everybody out.

Two optional client checks:

```powershell
node scripts\check-client.mjs          # parses every client module
node --experimental-websocket scripts\smoke-client.mjs
```

The second one starts a headless browser, signs in as a teacher and as a student, visits
every screen and fails on the first console error.

The third one goes further: it drives the camera with a fake device that plays a real
portrait and a real classroom code, so the identity check, the code scanner and the
screens are tested with pictures and not with a stub.

```powershell
# The picture of the classroom code, drawn with the library of the application.
node --experimental-websocket scripts\qr-picture.mjs target\qr.png

# The camera: the face and the code in one frame, and a face that turns.
java scripts\TurnFace.java src\test\resources\fixtures\portrait-a.jpg target\face-left.jpg -60 193 300,20,400,500
java scripts\FakeCamera.java target\camera.y4m 640 480 "target\face.jpg+target\qr.png:10" "target\face-left.jpg+target\qr.png:10"

# The check itself.
node --experimental-websocket scripts\verify-features.mjs
```

And a soak, which leaves the server alone for a while and then checks that the database
is still reachable. That is the check that catches a pooler dropping idle connections:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\soak-database.ps1 -Minutes 10
```

To look at the data without changing it:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\query.ps1 "select email, face_state from app_user"
```

---

## Project layout

```
src/main/java/com/veriqra/app/
├── config/         configuration properties, security, web wiring
├── domain/         the account model and the API shapes
├── repository/     data access, one class per table group
├── security/       roles, the current account, the error type
├── service/
│   ├── auth/       registration, sign in, the login throttle
│   ├── attendance/ sessions, the QR token, the student flow
│   ├── biometric/  detection, alignment, embedding, liveness, enrolment
│   ├── report/     reporting and the export formats
│   └── storage/    enrolment images, local or Supabase Storage
└── web/            the controllers

src/main/resources/
├── db/migration/   the Flyway schema
└── static/         the PWA: markup, styles, client modules, icons, worker

scripts/            model download, icon generation, client checks
models/             the ONNX models (downloaded, not in source control)
```

---

## Configuration

Every setting has an environment variable. The full list is in `.env.example`. The ones
that matter most:

| Variable | Meaning |
| --- | --- |
| `QR_SIGNING_SECRET` | Signs the rotating code. Rotating it invalidates every code. |
| `DATABASE_URL` | The Supabase connection string. |
| `DATABASE_POOL_SIZE` | How many connections the pool keeps. |
| `DATABASE_KEEPALIVE_MS` | How often idle connections are pinged. Must be 30000 or more. |
| `DATABASE_MAX_LIFETIME_MS` | How long a connection may live. |
| `INSTITUTION_NAME` | The name of the college. |
| `INSTITUTION_LOCATION` | Where the college is. |
| `DEMO_DATA_ENABLED` | Whether the demonstration accounts and classes are created. |
| `TEACHING_DAY_START` / `TEACHING_DAY_END` | The hours in which lectures may be scheduled. |
| `ATTENDANCE_OPEN_BEFORE_MINUTES` | How long before a lecture attendance may be opened. |
| `ATTENDANCE_OPEN_AFTER_MINUTES` | How long after a lecture attendance may still be opened. |
| `SUPABASE_SERVICE_ROLE_KEY` | Used by the server for Storage. Never reaches the browser. |
| `VERIQRA_MODELS_DIR` | Where the ONNX models live. |
| `BIOMETRIC_PROFILE` | The pair of models: `yunet-sface` or `scrfd-arcface`. |
| `BIOMETRIC_DETECTION_THRESHOLD` | The confidence a detection has to reach. Zero uses the threshold of the pair. |
| `BIOMETRIC_MIN_HIGHLIGHT_LUMA` | The luminance the brightest tenth of the face has to reach. |
| `BIOMETRIC_MAX_MIDDLE_LUMA` | The luminance the middle of the face must not go past. |
| `BIOMETRIC_MATCH_THRESHOLD` | The cosine similarity a live face must reach. |
| `BIOMETRIC_LIVENESS_YAW_DELTA` | How far the head must turn to prove liveness. |
| `SESSION_TIMEOUT_MINUTES` | How long a session lasts. The sessions live in the database. |
| `INSTITUTION_TIMEZONE` | The timezone the times are shown in. |

---

## Documentation

* [DESIGN.md](DESIGN.md) — the design system: the tokens, the shell, the parts and the rules.
* [API.md](API.md) — every route, with its scope and its request and response shapes.
* [SECURITY.md](SECURITY.md) — the threat model, the controls and the honest limits.
* [BUILD_STATUS.md](BUILD_STATUS.md) — what is done, what is verified and what is not.

---

## Licence

The YuNet and SFace models are published by the OpenCV model zoo and are covered by the
MIT and the Apache-2.0 licences. The SCRFD and ArcFace models of the stronger pair come
from the InsightFace model family, whose weights are published for non commercial
research. The QR libraries are vendored under their own MIT licences.

The two portraits in `src/test/resources/fixtures` are official White House portraits of
two former presidents. They are works of the United States federal government and are in
the public domain; they were taken from the MIT licensed `face_recognition` project and
are used to verify the face detection and recognition pipeline.
