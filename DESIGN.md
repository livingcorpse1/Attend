# The interface of VeriQra

This is the working reference for the interface. It describes what the screens look like,
what the rules are, and which parts are still open for decision. Every rule here is
enforced somewhere in `src/main/resources/static`, so if the code and this document
disagree, the code wins and this document is wrong.

The interface is built on the **Geist** design language: the type, the neutral scale, the
radii, the shadows and the component shapes that Vercel uses.

---

## 1. The shape of the product

VeriQra is used in two very different situations, and the interface has to work in both.

| | On a phone, in a lecture hall | On a laptop, at a desk |
| --- | --- | --- |
| Who | A student, one handed, in a hurry | A teacher, between classes |
| Light | Dim, unpredictable | Normal |
| Time | Seconds | Minutes |
| Network | Often poor | Usually fine |

So the phone layout is not the laptop layout with a smaller screen. The navigation moves
to a bottom bar, the touch targets grow, and the screens that need a camera get the
camera and nothing else.

The interface is a **vanilla JavaScript single page app**. There is no framework, no
build step and no bundler. The files in `src/main/resources/static` are served exactly
as they are on disk.

```
static/
  index.html          the shell, one page for every route
  css/
    tokens.css        the colours, the spacing, the type, the shadows
    base.css          the reset and the small utilities
    components.css    the reusable pieces: buttons, cards, tables, modals
    layout.css        the app shell and the page arrangements
  js/
    dom.js            h(), the hyperscript helper
    icons.js          the inline SVG icon set
    api.js            the fetch wrapper and the response cache
    store.js          the session and the theme
    router.js         the hash router
    ui.js             toasts, modals, tables, stat tiles, page headers
    main.js           the entry point, the shell, the routes
    views/            one file per screen
```

---

## 2. The rules

These are the decisions the interface is built on. They are not suggestions.

### 2.1 The neutral scale is the interface

Colour is a step on a scale, never a hue of its own. The scale runs from near black to
near white, and a surface, a line and a piece of text are just steps on it.

| Token | Light | Dark | Used for |
| --- | --- | --- | --- |
| `--background` | `#ffffff` | `#000000` | The page and every card |
| `--surface` | `#fafafa` | `#0a0a0a` | Table headers, footers, quiet panels |
| `--surface-2` | `#f2f2f2` | `#111111` | Hover, the active navigation entry |
| `--surface-3` | `#ebebeb` | `#1a1a1a` | Meters, avatars, icon wells |
| `--border` | `#eaeaea` | `#2e2e2e` | Every hairline |
| `--border-strong` | `#d4d4d4` | `#404040` | The edge of an input |
| `--text-primary` | `#0a0a0a` | `#ededed` | Headings and body text |
| `--text-secondary` | `#666666` | `#a1a1a1` | Descriptions, table cells |
| `--text-tertiary` | `#8f8f8f` | `#7d7d7d` | Labels, captions, hints |

### 2.2 One accent, and it is not the primary button

The accent is blue, `--accent: #0070f3`. It is used for links, focus rings and the
things you can act on. It is **not** used for the primary button.

**A primary button is ink.** Black in the light theme, white in the dark theme, using
`--foreground` on `--background`. That is what separates a tool from a toy: the buttons
recede and the data comes forward. It also means the accent is free to mean "this is
interactive" everywhere else.

### 2.3 Three semantic colours, and nothing else is saturated

| Colour | Light | Dark | It means |
| --- | --- | --- | --- |
| Success | `#0d7d4d` | `#50e3c2` | A thing went right. Present, verified, healthy. |
| Warning | `#a85e05` | `#f5a623` | A thing needs attention. Low attendance, a pending check. |
| Danger | `#e5484d` | `#ff6369` | A thing went wrong. Absent, failed, revoked. |

Each one has a soft background and a line for the state it sits in. Nothing in the
interface is a colour of its own, it is a step on the scale or one of these three.

### 2.4 Nothing glows

Elevation is a hairline border and, at most, a very soft shadow. No gradients on
surfaces, no coloured glows, no glass. The interface is flat and gets its shape from
borders and spacing.

### 2.5 Four radii

`6px`, `8px`, `12px`, `16px`, and a pill. A button and an input are `6px`, a card and a
menu are `12px`, a modal is `12px`. A tool should look like it was made, not like it was
decorated.

### 2.6 One type family, four weights

**Geist** for the interface, **Geist Mono** for times, codes and anything that has to
line up in a column. The weights used are regular, medium, semibold and bold. There is
no third family.

The scale is 12, 13, 14, 16, 20, 24, 32 and 40 pixels. Body text is 14. Table headers
are 12, uppercase, wide letter spacing, in the tertiary text colour. The number in a
stat tile is 32, semibold, with tight tracking.

### 2.7 Space is a scale, not a guess

`--space-1` through `--space-16`, doubling from `0.25rem`. Every gap, every padding and
every margin comes from the scale. `1rem` is the base unit of the layout.

### 2.8 The icon set is one drawing

Icons are inline SVG on a 24 unit grid, with a stroke of `1.6` and round caps. They
inherit the colour of the text they sit next to and they never cause a flash. There is
no icon font and there are no unicode glyphs standing in for icons.

### 2.9 Every control has three states

Rest, hover and focus. The focus ring is never removed, only moved, and it is always
visible against the surface it sits on. Disabled controls are dimmed to `50%` and never
hidden, so the layout does not jump when the state of a form changes.

### 2.10 Nothing is announced by colour alone

Every status carries a word. Every icon button carries a label for the screen reader.
Every table column carries a header. Every form field carries a label.

### 2.11 Motion is short and optional

Transitions are between `100ms` and `250ms`. Anything that moves respects
`prefers-reduced-motion`, which collapses every duration to zero.

---

## 3. The shell

```
┌────────────┬──────────────────────────────────────┐
│            │  Topbar: page title · actions        │
│  Rail      ├──────────────────────────────────────┤
│            │                                      │
│  brand     │  Content, max 1400px, centred       │
│  nav       │                                      │
│  account   │                                      │
└────────────┴──────────────────────────────────────┘
```

### The rail

248px wide, the same background as the page, separated by a hairline. It carries the
brand, the navigation, and the signed in account.

The active entry is marked by a `--surface-2` background and a brighter text. The label
comes back on hover when the rail is collapsed, because an icon is not a name.

**Compact, under 900px.** The rail shrinks to 68px and the labels are replaced by
icons.

**Phone, under 640px.** The rail becomes a bottom bar above the safe area, and the
content is padded so the bar never covers the last row of a table.

### The account menu

The account sits at the bottom of the rail with the avatar, the name and the role. It
opens a menu with the email address and the way out. The focus goes back to the button
when the menu closes.

### The top bar

52px, sticky, translucent with a blur behind it. It carries the name of the current
screen and the actions that belong to every screen: the institution and the theme
toggle.

---

## 4. The parts

### Buttons

| Class | Use |
| --- | --- |
| `.button-primary` | The one action of the screen. Ink, not accent. |
| `.button-secondary` | Everything else that acts. |
| `.button-ghost` | Tertiary, in place of text. |
| `.button-danger` | Destroys something. Filled red. |
| `.button-danger-secondary` | Destroys something, quietly. Outlined red. |

32px tall, 6px radius, 14px text, 12px of padding on each side. `.button-sm` is 24px,
`.button-lg` is 40px, `.button-block` fills its parent, and `.is-loading` swaps the
label for a spinner.

### Cards

A surface with a hairline border and the smallest shadow, 12px radius. A card is either
a container or a single subject. Cards that hold a list use `.card-body-flush` so the
list runs to the edges of the card.

### Stat tiles

A small icon, a label in small caps, a large number, and a line of explanation. The
number is coloured only when the number itself is a judgement: green above the
threshold, ochre close to it, brick below. A meter shows where the number sits between
zero and a hundred.

### Tables

Hairline rows, no stripes, a small uppercase header, and tabular numbers so the
columns line up. The first column is the subject of the row and is set in medium
weight. Numbers are right aligned, everything else is left aligned. A table that is
wider than its card scrolls inside the card, never sideways across the page.

### Empty states

An icon in a soft square and a line of text. An empty state is never an error and never
shouts.

### Toasts

Bottom right, ink in the light theme, with the semantic colour when it is a success or
an error. They dismiss themselves. They never carry the only copy of a message.

### Modals

A scrim with a blur behind it, a card, and three zones: a header that does not move, a
body that scrolls, and a footer that does not move. The focus is trapped inside the
dialog, `Escape` closes it, the page behind it cannot scroll, and the focus returns to
the field that opened it.

---

## 5. The screens

### Sign in and sign up

A split screen. The left half is black with a blue glow and a faint grid, and it
explains what the product does. The right half is the form. Under 1080px the left half
becomes a banner and the form takes the whole width.

The password field has a reveal button.

### Student dashboard

Four stat tiles across the top: overall attendance against the target, sessions
attended, classes taking attendance right now, and the state of the identity check.
Then attendance by subject, the recent check ins, and the week as a scheduler.

### Verify presence

The screen a student opens in a lecture hall. Scan the code, follow the instruction,
done. It is three states, one at a time, and the camera is the largest thing on the
screen. The manual entry box is there for when the camera will not start, and it is
quietly placed below the scanner rather than competing with it.

### Take attendance

The teacher opens a session, and a rotating code is projected. The live roster is a
full width table under it, refreshing every three seconds, and the teacher can correct
any record with a reason, which is written to the audit trail.

### Timetable

A real week. The days across the top, the hours down the side, each lecture placed at
the height that matches its time. A teacher can add and move their own lectures, and
the server refuses a double booking.

### Reports

Totals by subject, and the students below the threshold. Both export to CSV and PDF.

---

## 6. Open decisions

These are the questions the facelift still has to answer. They are the reason this
branch exists.

1. **The first thing on the screen.** At the moment the student dashboard leads with
   four numbers. Should it lead with the next class instead, the one thing the student
   came to do?
2. **How dense should a table be.** The roster is comfortable to read at a glance, but
   it is also a wall of text. Is a compact variant worth it for the teacher?
3. **Does the live session need a different shape.** The code and the roster are on the
   same screen but on different rows. Side by side would let a teacher watch the room
   fill while the code is on the projector.
4. **Does the phone layout need its own screens,** or is a responsive desktop layout
   enough?
5. **How loud should the warnings be.** Low attendance is the one number in the
   product that a student should not be able to ignore. Ochre may not be enough.
6. **Icons.** The set is defined in `js/icons.js` and is deliberately small. It may
   need to grow, or the rail may need to find another way to be short.

---

## 7. The rules for changing this

- One token file. If a colour is needed and it is not in `tokens.css`, it does not go
  into a component.
- No unicode in place of an icon. If the icon does not exist, it gets drawn.
- A new colour has to say what it means.
- A new animation has to respect `prefers-reduced-motion`.
- A new control needs a hover and a focus state, and needs to survive being rendered
  by a screen reader.
- The phone layout is not optional.
- A table that is too wide for its card scrolls inside the card. The page never scrolls
  sideways.
