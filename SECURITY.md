# Security

This document describes what VeriQra defends against, how, and where the honest limits
are. The limits are written down on purpose: a system that claims to be infallible is
harder to trust than one that says what it cannot do.

---

## The trust boundary

Everything that decides whether a student is present happens on the server.

The browser is allowed to do exactly two things:

1. show pixels the camera captured,
2. send them to the server.

It cannot assert that a face was detected, that a liveness check passed, that a match
was good enough, or that a code was genuine. There is no code path that writes an
attendance record from a browser supplied verdict.

```
browser  ──pixels──▶  server  ──detect / align / embed / compare──▶  decision
   ▲                     │
   └── result only ──────┘
```

---

## Threats and the controls

### A student marks attendance for somebody else

**Control.** The attendance record is only written after the live descriptor is compared
with the enrolled template of the account that scanned the code. The account is resolved
from the session, never from the request body. The enrolment is the only source of the
identity.

**Limit.** A student who passes the camera to a colleague who looks sufficiently like
them could mark their own attendance. This is the classic face recognition limitation.
The active liveness challenge narrows the window to a few seconds, and the match
threshold can be raised, but the problem is not solved by software alone. Pair the
system with an occasional roll call.

### A student photographs the projected code and reuses it

**Control.** The payload is signed with HMAC-SHA-256 and expires after eight seconds. A
new payload with a new random component is issued every second. The payload is bound to
the session nonce, so closing and reopening a session invalidates every code that was
issued before. A ticket is handed out per scan, expires after two minutes and can be
used exactly once.

**Limit.** A student who photographs the code *and* is marked present by a face check
for somebody else is still a problem. The code alone is not enough; the face check is
what carries the weight.

### A student replays a video of themselves to the camera

**Control.** The server picks the challenge at random and expects the head pose to
change in the requested direction by a measurable amount. The frames must also differ
from each other, so a paused recording does not pass.

**Limit.** A sophisticated attacker who reacts to the challenge in real time, for
example by holding a phone that shows a deepfake, can get past an active challenge. The
defence in depth for that is a passive presentation attack detector, which this build
does not include. See *Known limitations*.

### A student opens the network and reads other people's attendance

**Control.** Every route requires an authenticated session. The role is checked in the
URL rules and re-checked in the service layer against the database. A teacher can only
reach their own sessions; a student can only reach their own records. Object level
checks are performed per row, not per route.

### A student brute forces somebody else's password

**Control.** Passwords are hashed with BCrypt at cost 12. Failed sign ins are recorded
with the identifier and the source address. The service refuses further attempts once
eight failures for an identifier, or forty for an address, occur inside fifteen
minutes. The attempt log is pruned after thirty days.

### A cross site request forges an attendance record

**Control.** CSRF protection is enabled with a double submit token. The client reads
the `XSRF-TOKEN` cookie and echoes it in the `X-XSRF-TOKEN` header. The session cookie
is `HttpOnly` and `SameSite=Lax`.

### An attacker injects a script into the client

**Control.** The content security policy allows only this origin: no inline scripts, no
inline styles, no third party hosts. The client builds every node through a helper
instead of concatenating strings, so text from the server is inserted as a text node
and can never become markup. The QR libraries are vendored, so there is no third party
supply chain in the browser.

### An administrator reads the biometric data

**Control.** The service role key for Supabase Storage lives only on the server. The
browser never sees it. The API has no route that returns an enrolment photograph.

### A stolen database leaks the biometric data

**Control.** The stored template is a 128 dimensional descriptor, not an image. It
cannot be turned back into a face. The enrolment photographs live in a Supabase Storage
bucket, which can be a private bucket with its own access rules.

---

## Cryptography

| Purpose | Method |
| --- | --- |
| Passwords | BCrypt, cost factor 12 |
| QR payload | HMAC-SHA-256, base64url, compared in constant time |
| Session identifier | Provided by the servlet container, `HttpOnly`, `SameSite=Lax` |
| CSRF token | Provided by Spring Security, double submit |

`QR_SIGNING_SECRET` must be at least 64 random characters. Rotating it invalidates every
projected code, which is the correct behaviour after a suspected leak.

---

## Headers

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self';
  img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self';
  font-src 'self'; worker-src 'self'; manifest-src 'self'; base-uri 'self';
  form-action 'self'; frame-ancestors 'none'
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
Permissions-Policy: camera=(self), microphone=(), geolocation=(), payment=(), usb=()
Strict-Transport-Security: max-age=31536000; includeSubDomains
Cache-Control: no-cache, no-store, max-age=0, must-revalidate   (on API responses)
```

---

## Operational checklist before going live

* [ ] `QR_SIGNING_SECRET` set to at least 64 random characters.
* [ ] `BOOTSTRAP_ADMIN_PASSWORD` set, and the generated password changed.
* [ ] `SPRING_PROFILES_ACTIVE=prod`, so the demonstration accounts are not created.
* [ ] `SESSION_COOKIE_SECURE=true` and the application served over HTTPS.
* [ ] `STORAGE_PROVIDER=supabase` with a private bucket and a rotated service role key.
* [ ] Row level security reviewed on the Supabase project. The application connects
      with the `postgres` role, which bypasses row level security by design; the
      application is the enforcement point. If a dashboard is used, keep the direct
      connection string out of it and use the pooler with a read only role.
* [ ] `veriqra.biometric.match-threshold` tuned against a sample of real students.
* [ ] The audit trail exported to a place the institution keeps.
* [ ] The privacy notice for the biometric data reviewed by the college and published
      to the students.

---

## Known limitations

These are real and are not worked around in the documentation.

1. **No passive presentation attack detection.** The build detects a photo by the
   active challenge, not by texture analysis. A screen or a print that is turned in
   real time can pass. Adding a silent liveness model (MiniFASNet for example) would
   raise the bar further.
2. **The liveness challenge is a head turn.** It is reliable and easy to explain, but
   it is not the strongest possible challenge. A blink or a facial expression challenge
   would need a landmark model.
3. **Face recognition is not infallible.** The thresholds are tuned conservatively, which
   means genuine students with an unusual appearance may be rejected more often. The
   fallback is an audited correction by the teacher.
4. **The enrolment image is stored.** The template cannot be reversed, but the
   reference photograph can. The privacy notice covers this and the student can delete
   it.
5. **The login throttle is stored in the database.** It survives a restart, but it is a
   simple counter, not a distributed limiter. A burst across many source addresses is
   not caught by the address limit.
6. **There is no second factor.** A stolen password is enough to sign in. A one time
   password over a second channel would be the next step.
7. **The session is not bound to the device.** A stolen session cookie is a stolen
   session. The cookie is `HttpOnly` and `SameSite=Lax`, which helps, but it does not
   help against malware on the device.
8. **Audit entries cannot be deleted through the API.** They can still be deleted by
   anyone with database access, so the database role should not allow it in production.
