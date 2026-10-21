# The interface of VeriQra

This is the working reference for the interface. It describes what the screens look like
today, what the rules are, and which parts are still open for decision. Every rule here
is enforced somewhere in `src/main/resources/static`, so if the code and this document
disagree, the code wins and this document is wrong.

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
    ui.js             toasts, modals, tables, stat tiles
    main.js           the entry point, the shell, the routes
    views/            one file per screen
```

---

## 2. The rules

These are the decisions the interface is built on. They are not suggestions.

### 2.1 Colour is a signal, not a decoration

The palette is deliberately narrow. Warm neutrals carry the interface, and exactly three
colours carry meaning:

| Colour | Token | It means |
| --- | --- | --- |
| Evergreen | `--brand` | A thing went right. Present, verified, healthy. |
| Ochre | `--warning` | A thing needs attention. Low attendance, a pending check. |
| Brick | `--danger` | A thing went wrong. Absent, failed, revoked. |

There is no blue and no purple, because those read as a default rather than as a
decision.

**Primary actions are ink, not a brand colour.** A primary button is near black, never
green. That is what separates a tool from a toy: the buttons recede and the data comes
forward. Green is reserved for meaning, so a green button would dilute the one signal
that matters.

Every colour that carries text clears 4.5:1 against the surface it sits on. The muted
tier is muted, not faded. Colour is never the only carrier of meaning: a status always
has a word next to it.

### 2.2 Nothing glows

Elevation is a hairline border and, at most, a very soft shadow. No gradients on
surfaces, no coloured glows, no glass. The interface is flat and gets its shape from
borders and spacing.

### 2.3 Radii are restrained

`4px`, `7px`, `10px`, `14px`, and a pill. One step down from the roundness that is
fashionable. A tool should look like it was made, not like it was decorated.

### 2.4 Type does the work

One family, `Inter`, falling back to the system stack. Four weights are used: regular,
medium, semibold and bold. Headings are tight and slightly negative letter spaced,
body text is loose and never below `0.8125rem`.

The display sizes carry the hierarchy:

| Token | Size | Used for |
| --- | --- | --- |
| `--text-3xl` | 2rem | The number in a stat tile |
| `--text-2xl` | 1.5rem | The title of a page |
| `--text-xl` | 1.25rem | A section heading |
| `--text-md` | 0.9375rem | A card heading, a label |
| `--text-base` | 0.875rem | Body text, buttons, inputs |
| `--text-sm` | 0.8125rem | Tables, secondary text |
| `--text-xs` | 0.6875rem | Eyebrows, table headers, badges |

### 2.5 Space is a scale, not a guess

`--space-1` through `--space-16`, doubling from `0.25rem`. Every gap, every padding and
every margin comes from the scale. `1rem` is the base unit of the layout.

### 2.6 The icon set is one drawing

Icons are inline SVG on a 24 unit grid, with a stroke of `1.7` and round caps. They
inherit the colour of the text they sit next to and they never cause a flash. There is
no icon font and there are no unicode glyphs standing in for icons.

### 2.7 Every control has three states

Rest, hover and focus. The focus ring is never removed, only moved, and it is always
visible against the surface it sits on. Disabled controls are dimmed to `45%` and never
hidden, so the layout does not jump when the state of a form changes.

### 2.8 Nothing is announced by colour alone

Every status carries a word. Every icon button carries a label for the screen reader.
Every table column carries a header. Every form field carries a label.

### 2.9 Motion is short and optional

Transitions are between `110ms` and `280ms`. Anything that moves respects
`prefers-reduced-motion`, which collapses every duration to zero.

---

## 3. The shell

```
┌────────────┬──────────────────────────────────────┐
│            │  Topbar: page title · actions        │
│  Sidebar   ├──────────────────────────────────────┤
│            │                                      │
│  brand     │  Content, max 1360px, centred       │
│  nav       │                                      │
│  account   │                                      │
└────────────┴──────────────────────────────────────┘
```

### Sidebar

248px wide, dark ink, sticky, always in the same place. It carries the brand, the
navigation, and the signed in account with the sign out button.

The active entry is marked three ways at once: a lighter background, a brighter
weight, and a green rule on the left edge. The rule stays visible when the sidebar
collapses, which is what makes the compact layout work.

**Compact, under 900px.** The sidebar shrinks to 76px and the labels are replaced by
icons. A label comes back on hover, because an icon is not a name.

**Phone, under 640px.** The sidebar becomes a bottom bar above the safe area, and the
content is padded so the bar never covers the last row of a table.

### Topbar

62px, sticky, translucent with a blur behind it. It carries the name of the current
screen and the actions that belong to every screen: the institution, the theme toggle
and the account menu.

The topbar repeats the page title that the page itself also shows. That is deliberate:
the topbar is the frame and the page heading is the content, and they are allowed to
say the same thing in different words.

---

## 4. The parts

### Buttons

| Class | Use |
| --- | --- |
| `.button-primary` | The one action of the screen. Ink, not brand. |
| `.button-secondary` | Everything else that acts. |
| `.button-ghost` | Tertiary, in place of text. |
| `.button-danger` | Destroys something. Outlined, never filled. |
| `.button-brand` | Rare. Only when the action is a verification. |

`.button-sm` and `.button-lg` change the size, `.button-block` fills its parent, and
`.is-loading` swaps the label for a spinner.

### Cards

A surface with a hairline border and the smallest shadow. A card is either a container
or a single subject. Cards that hold a list use `.card-body-flush` so the list runs to
the edges of the card, and a sticky table header keeps the columns readable while the
list scrolls.

### Stat tiles

A label in small caps, a large number, and a line of explanation. The number is
coloured only when the number itself is a judgement: green above the threshold, ochre
close to it, brick below. A meter shows where the number sits between zero and a
hundred.

### Tables

Hairline rows, no stripes, a sticky header, and tabular numbers so the columns line up.
The first column is the subject of the row and is set in semibold. Numbers are right
aligned, everything else is left aligned.

### Empty states

An icon in a soft square, a line of text, and nothing else. An empty state is never an
error and never shouts.

### Toasts

Bottom right, four corners of ink, with the variant colour when it is a success or an
error. They dismiss themselves. They never carry the only copy of a message.

### Modals

A scrim, a card, and three zones: a header that does not move, a body that scrolls, and
a footer that does not move. Focus is trapped inside the dialog, `Escape` closes it, and
focus returns to the button that opened it.

---

## 5. The screens

### Sign in and sign up

A split screen. The left half is dark and explains what the product does, the right half
is the form. Under 1080px the left half becomes a banner and the form takes the whole
width.

### Student dashboard

Four stat tiles across the top: overall attendance against the target, sessions
attended, classes taking attendance right now, and the state of the identity check. Then
attendance by subject, the recent check ins, and the week as a scheduler.

### Verify presence

The screen a student opens in a lecture hall. Scan the code, follow the instruction,
done. It is three states, one at a time, and the camera is the largest thing on the
screen. The manual entry box is there for when the camera will not start, and it is
quietly placed below the scanner rather than competing with it.

### Take attendance

The teacher opens a session, and a rotating code is projected. The live roster fills in
under it, refreshing every three seconds, and the teacher can correct any record with a
reason, which is written to the audit trail.

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
3. **What does the live session look like.** The rotating code and the roster are
   stacked. Side by side would let a teacher watch the room fill while the code is on
   the projector.
4. **Does the phone layout need its own screens,** or is a responsive desktop layout
   enough?
5. **How loud should the warnings be.** Low attendance is the one number in the
   product that a student should not be able to ignore. Ochre may not be enough.
6. **Icons.** The set is defined in `js/icons.js` and is deliberately small. It may
   need to grow, or the sidebar may need to find another way to be short.

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
