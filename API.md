# API reference

Every route is below `/api`. Responses are JSON unless stated otherwise.

## Conventions

* **Authentication** is a session cookie (`JSESSIONID`). The client is a browser
  application, so there is no token to refresh.
* **CSRF** is enforced on every state changing request. The client reads the
  `XSRF-TOKEN` cookie and echoes it in the `X-XSRF-TOKEN` header. A request without a
  valid token is answered with `403`.
* **Errors** always use the same body:

  ```json
  {
    "code": "QR_INVALID",
    "message": "The QR code signature does not match. Scan the current code.",
    "status": 400,
    "timestamp": "2026-01-01T09:15:00Z",
    "path": "/api/student/attendance/scan"
  }
  ```

  `code` is stable and safe to branch on, `message` is written for a person.

### Error codes

| Code | Status | Meaning |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | The payload did not pass the field validation. |
| `MISSING_PARAMETER` | 400 | A required parameter is missing. |
| `INVALID_CREDENTIALS` | 401 | Wrong email or password. |
| `UNAUTHENTICATED` | 401 | No session. |
| `FORBIDDEN` | 403 | The session is valid but not allowed to do this. |
| `NOT_AUTHORIZED` | 403 | The caller does not own the resource. |
| `FACE_VERIFICATION_REQUIRED` | 403 | The student has not completed the identity check. |
| `FACE_NOT_VERIFIED` | 403 | The student has no face template. |
| `NOT_ENROLLED` | 403 | The student is not in the class. |
| `CHALLENGE_EXPIRED` / `CHALLENGE_USED` | 400 / 409 | The liveness challenge is no longer valid. |
| `TICKET_EXPIRED` / `TICKET_USED` / `TICKET_UNKNOWN` | 400 / 409 / 400 | The attendance ticket is no longer valid. |
| `QR_INVALID` | 400 | The scanned code is forged, expired or belongs to another session. |
| `ALREADY_RECORDED` | 409 | Attendance for this session exists. |
| `SESSION_NOT_ACTIVE` / `SESSION_EXPIRED` | 409 | The class session is closed. |
| `SLOT_NOT_OPEN` | 409 | The lecture is not running, so its attendance may not be taken. |
| `NOT_OWNER` | 403 | The lecture, the subject or the class belongs to another teacher. |
| `CLASS_EXISTS` | 409 | The teacher already has a class section with that name for that subject. |
| `MODEL_CHANGED` | 400 | The template was enrolled with another pair of models, so the identity check has to be run again. |
| `LOGIN_THROTTLED` / `SUBMISSION_TOO_FAST` | 429 | The client is going too fast. |
| `BIOMETRIC_UNAVAILABLE` | 400 | The models are not installed on the server. |

---

## Public routes

### `GET /api/health`

Liveness probe. Reports whether the biometric pipeline can run.

```json
{ "status": "UP", "time": "2026-01-01T09:15:00Z", "biometricAvailable": true }
```

### `GET /api/auth/session`

Bootstrap call. Returns the CSRF token, the signed in account (or `null`) and the
institution details. Always callable without a session.

```json
{
  "csrfToken": "b1f0…",
  "account": { "id": 4, "email": "a@college.edu", "fullName": "Aarav Sharma", "role": "STUDENT", "active": true, "studentNumber": "VQ24001", "faceState": "PENDING", "faceVerifiedAt": null, "createdAt": "2026-01-01T08:00:00Z" },
  "institution": { "name": "", "location": "" },
  "consentVersion": "2026-01",
  "demoMode": true,
  "demoAccounts": ["faculty@veriqra.local / Demo!2026"]
}
```

The name and the location of the institution come from the institution settings, which
are read from the environment. The consent version tells the client
which version of the biometric notice to send. The demonstration accounts are only
listed when they exist.

### `POST /api/auth/login`

```json
{ "email": "student@college.edu", "password": "…" }
```

Answers `200` with the account profile, or `401 INVALID_CREDENTIALS`. Repeated failures
are throttled per identifier and per address.

### `POST /api/auth/register`

Creates a student account and signs it in. The account starts in the `PENDING` face
state.

```json
{
  "fullName": "Aarav Sharma",
  "email": "aarav@college.edu",
  "password": "at least 10 characters",
  "studentNumber": "VQ24001",
  "consent": "2026-01",
  "consentAccepted": true
}
```

Answers `201` with the account profile. `consentAccepted` must be `true` and `consent`
must be the current notice version.

### `POST /api/auth/logout`

Invalidates the session. Answers `200`.

### `GET /api/auth/me`

The signed in account.

---

## Student routes

All routes below require the `STUDENT` role. The routes that only read are open before the
identity check has been done, so a student whose check is still open keeps the
application. The two routes that mark an attendance need a verified face; otherwise the
answer is `403 FACE_VERIFICATION_REQUIRED`.

### `GET /api/student/dashboard`

The numbers, the per subject totals, the recent check-ins and the timetable. Open before
the identity check has been done, so a student whose check is still open is not locked
out of the application.

### `GET /api/student/attendance`

The attendance history, newest first. Open before the identity check has been done.

```json
[
  { "recordedAt": "2026-01-01T09:15:00Z", "status": "PRESENT", "method": "FACE_VERIFIED", "matchScore": 0.81 }
]
```

### `GET /api/student/timetable`

The weekly schedule of the classes the student is enrolled in. Open before the identity
check has been done.

### `GET /api/student/sessions/active`

The classes that are taking attendance right now.

### `GET /api/student/biometric/status`

```json
{
  "state": "PENDING",
  "enrolledAt": null,
  "modelVersion": "yunet-2023mar+sface-2021dec",
  "sampleCount": 0,
  "available": true,
  "unavailableReason": null,
  "consentVersion": "yunet-2023mar+sface-2021dec"
}
```

`modelVersion` is the version of the template that is stored, `consentVersion` the version
of the models the server runs with. When the two differ the template was enrolled with
another pair of models and the student has to run the identity check again.

### `POST /api/student/biometric/challenge`

Issues a random liveness challenge.

```json
{
  "challengeId": "0f5c…",
  "instruction": "Turn your head to your LEFT",
  "guidance": "Look straight at the camera first, then slowly turn your head…",
  "expiresAt": "2026-01-01T09:17:00Z",
  "requiredFrames": 5,
  "frameIntervalMillis": 320
}
```

### `POST /api/student/biometric/enroll`

`multipart/form-data` with one `frames` part per captured frame and a `challengeId`
part. Runs the whole check and stores the template.

```json
{ "enrolled": true, "message": "Your face is verified…", "state": "VERIFIED", "qualityScore": 0.81, "minSimilarity": 0.74, "enrolledAt": "…" }
```

### `DELETE /api/student/biometric/template`

Removes the template and the enrolment image. The account returns to the `REVOKED`
state and can no longer mark attendance.

### `POST /api/student/attendance/scan`

Validates a scanned code and issues a ticket for the face check.

```json
{ "token": "VQ1.42.nonce.1767260000.1767260008.random.signature" }
```

```json
{
  "ticketId": "9b1e…",
  "challengeId": "0f5c…",
  "challengeInstruction": "Turn your head to your RIGHT",
  "challengeGuidance": "…",
  "sessionId": 42,
  "subjectCode": "CS301",
  "subjectName": "Distributed Systems",
  "section": "Section A",
  "room": "Innovation Lab 204",
  "facultyName": "Dr. Maya Rao",
  "expiresAt": "2026-01-01T09:17:00Z",
  "requiredFrames": 5
}
```

The session is derived from the signed payload, so the student only has to scan.

### `POST /api/student/attendance/submit`

`multipart/form-data` with the `frames` parts and a `ticketId` part.

```json
{ "outcome": "RECORDED", "message": "Your attendance has been recorded.", "recordId": 88, "matchScore": 0.81, "livenessScore": 1.0, "recordedAt": "…" }
```

`outcome` is `RECORDED`, `REJECTED` or `ALREADY_RECORDED`.

---

## Teacher routes

All routes below require the `FACULTY` role. A teacher owns their own academic
structure, and nobody can touch anybody else's.

### `GET /api/faculty/dashboard`

The totals, the recent sessions, the per subject figures and the whole teaching diary,
which the client turns into the fortnight calendar.

### `GET /api/faculty/classes`

The class sections the teacher is assigned to.

### `GET /api/faculty/structure`

The subjects and the class sections of the teacher, in one read.

```json
{
  "subjects": [{ "id": 10, "code": "ML", "name": "Machine Learning" }],
  "classes": [{ "id": 11, "subjectId": 10, "subjectCode": "ML", "subjectName": "Machine Learning", "section": "A", "room": "Innovation Lab 204", "enrolled": 2 }]
}
```

### `POST /api/faculty/subjects` · `DELETE /api/faculty/subjects/{id}`

Creates a subject that belongs to the teacher, and removes one that has no class
sections.

```json
{ "code": "ML", "name": "Machine Learning" }
```

### `POST /api/faculty/classes` · `DELETE /api/faculty/classes/{id}`

Creates a class section for one of the teacher's own subjects, and removes it. A class
section that has attendance sessions is refused with `CLASS_HAS_SESSIONS`, because a
session is a record of what happened.

```json
{ "subjectId": 10, "section": "A", "room": "Innovation Lab 204" }
```

### `POST /api/faculty/enrollments` · `DELETE /api/faculty/enrollments`

Enrols a student into one of the teacher's own class sections, and removes them. The
student has to register first, so the enrolment is by email address.

```
POST /api/faculty/enrollments?email=student@college.edu&classId=11
```

### `POST /api/faculty/teachers`

Adds a colleague to the teaching staff. There is no administrator, so a teacher
provisions the other teachers. The generated password is returned once.

```json
{ "email": "colleague@college.edu", "fullName": "Prof. A. Iyer" }
```

### `GET /api/faculty/timetable`

The weekly schedule of the teacher.

### `GET /api/faculty/schedule`

The lectures of the teacher on the day of the request, with a note of whether the
attendance may be taken for them right now.

```json
[
  {
    "id": 7,
    "classId": 14,
    "subjectCode": "AIML",
    "subjectName": "Artificial Intelligence & Machine Learning",
    "section": "A",
    "room": "Innovation Lab 204",
    "dayOfWeek": 1,
    "starts": "10:00",
    "ends": "11:00",
    "open": false,
    "message": "That lecture starts at 10:00. Attendance opens 15 minutes before it."
  }
]
```

### `GET /api/faculty/sessions?status=ACTIVE&limit=25`

The sessions of the teacher, newest first. `status` accepts `ACTIVE`, `CLOSED`,
`EXPIRED` or `ALL`. A session whose time is up is never reported as running.

### `POST /api/faculty/sessions`

Opens a session for a lecture of the teaching diary.

```
POST /api/faculty/sessions?slotId=7
```

The lecture has to belong to the teacher and has to be running: the window opens
`ATTENDANCE_OPEN_BEFORE_MINUTES` minutes before the lecture and closes
`ATTENDANCE_OPEN_AFTER_MINUTES` minutes after it ends. A session for a lecture of
another teacher is refused with `NOT_OWNER`, and a lecture that is not running is
refused with `SLOT_NOT_OPEN`.

### `GET /api/faculty/sessions/{sessionId}`

One session.

### `GET /api/faculty/sessions/{sessionId}/qr`

The rotating code. The client refreshes it every second.

```json
{
  "payload": "VQ1.42.nonce.1767260000.1767260008.random",
  "signature": "…",
  "token": "VQ1.42.nonce.1767260000.1767260008.random.…",
  "issuedAt": 1767260000,
  "expiresAt": 1767260008,
  "expiresInSeconds": 8,
  "refreshSeconds": 1
}
```

### `POST /api/faculty/sessions/{sessionId}/close`

Ends the session. Students can no longer scan the code.

### `GET /api/faculty/sessions/{sessionId}/roster`

The live roster, including the students who have not checked in.

```json
[
  { "studentId": 4, "studentName": "Aarav Sharma", "studentNumber": "VQ24001", "status": "PRESENT", "recordedAt": "…", "matchScore": 0.81, "method": "FACE_VERIFIED", "faceVerified": true }
]
```

### `PATCH /api/faculty/sessions/{sessionId}/roster/{studentId}`

A teacher correction. It always writes an audit entry.

```json
{ "status": "PRESENT", "reasonCode": "VERIFIED_ROLL_CALL", "explanation": "Confirmed during roll call" }
```

`status` is `PRESENT`, `ABSENT` or `EXCUSED`. The explanation is mandatory and is
stored in the audit trail.

### `GET /api/faculty/timetable`

The teaching diary of the signed in teacher. Only their own lectures, and only for the
subjects they are assigned to.

```json
[
  { "id": 1, "classId": 1, "subjectCode": "CS301", "subjectName": "Distributed Systems",
    "section": "Section A", "room": "Innovation Lab 204", "dayOfWeek": 1,
    "starts": "09:00", "ends": "10:00", "facultyName": "Dr. Maya Rao" }
]
```

### `POST /api/faculty/timetable`

Adds a lecture to the diary.

```json
{ "classId": 1, "dayOfWeek": 1, "starts": "09:00", "ends": "10:00" }
```

The server refuses the request when the class is not one the teacher teaches
(`NOT_ASSIGNED`), when the lecture overlaps another of their own lectures
(`TEACHER_BUSY`), when the class is already booked (`CLASS_BUSY`), when it ends before
it starts (`INVALID_TIME_RANGE`), when it falls outside the teaching day
(`OUTSIDE_TEACHING_HOURS`) or when the weekday is not a real one (`INVALID_DAY`).

### `PATCH /api/faculty/timetable/{slotId}`

Moves or reschedules a lecture. The same rules apply. A teacher may only change their
own lecture; somebody else's is refused with `NOT_OWNER`.

### `DELETE /api/faculty/timetable/{slotId}`

Removes a lecture from the diary. Every change is written to the audit trail.

### `GET /api/faculty/reports/subjects`

The per subject totals.

### `GET /api/faculty/reports/low-attendance`

The students below the institutional threshold.

### `GET /api/faculty/reports/low-attendance.csv` · `.pdf`

The same list as a download.

---

## Shared routes

### `GET /api/audit-logs?limit=100`

The audit trail. A teacher sees the entries they wrote themselves. The trail is
append only.
