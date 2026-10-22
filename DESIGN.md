# The interface of VeriQra

This is the working reference for the interface. It describes what the screens look like,
what the rules are, and which parts are still open for decision. Every rule here is
enforced somewhere in `src/main/resources/static`, so if the code and this document
disagree, the code wins and this document is wrong.

The interface is **monochromatic**. There is no accent colour and no hue anywhere: every
surface, every line, every shadow and every state is a step on one scale from near black
to near white. The type is the Geist family, the depth comes from a pair of soft shadows
rather than from a colour, and what separates one state from another is its weight and
its fill.

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

### 2.1 Six greys carry the whole interface

There is no colour in the interface. Everything is one of six greys, and what tells two
things apart is their weight and their fill, never their hue.

| Token | Light | Dark | Used for |
| --- | --- | --- | --- |
| `--background` | `#f5f5f5` | `#0d0d0d` | The page |
| `--surface` | `#eaeaea` | `#151515` | Cards, the raised surfaces |
| `--surface-inset` | `#f0f0f0` | `#101010` | The wells: inputs, icon boxes |
| `--border` | `#d0d0d0` | `#262626` | Every hairline |
| `--border-strong` | `#b5b5b5` | `#3a3a3a` | The edge of a control |
| `--text-primary` | `#0d0d0d` | `#f5f5f5` | Headings and body text |
| `--text-secondary` | `#626262` | `#b5b5b5` | Descriptions, table cells |
| `--text-tertiary` | `#8a8a8a` | `#8a8a8a` | Labels, captions, hints |

### 2.2 There is no accent, and the primary button is ink

`--accent` is the ink itself. It is used for links, focus rings and the things you can
act on, and it is **not** used for the primary button.

**A primary button is ink.** Black in the light theme, white in the dark theme, using
`--foreground` on `--background`. That is what separates a tool from a toy: the buttons
recede and the data comes forward.

### 2.3 The states are told apart by weight and fill

A state is never a colour, because there are no colours. It is a chip that is filled or
outlined, and the strength of the fill says how strong the state is.

| State | Light | Dark | It means |
| --- | --- | --- | --- |
| Success | A solid dark chip | A solid light chip | A thing went right. Present, verified, healthy. |
| Warning | A mid grey chip | A mid grey chip | A thing needs attention. Low attendance, a pending check. |
| Danger | An outlined chip with a strong border | An outlined chip with a strong border | A thing went wrong. Absent, failed, revoked. |

Every state carries a word as well, so the meaning never rests on the shape alone.

### 2.4 The depth is a pair of soft shadows

A surface is either raised out of the page or pressed into it. A raised surface carries a
dark shadow below and to the right and a light one above and to the left, so the light
seems to come from the top left. A pressed surface carries the same pair inside it.

Nothing glows. There is no coloured shadow, no gradient on a surface and no glass. The
shadows are black and grey, and they are quiet.

### 2.5 Four radii

`8px`, `12px`, `18px`, `26px`, and a pill. A button and an input are `8px`, a card and a
menu are `18px`, a modal is `18px`. A tool should look like it was made, not like it was
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
number is set in ink only when the number itself is a judgement: the strong ink above the
threshold, the middle grey close to it, the outlined ink below. A meter shows where the
number sits between
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

Bottom right, ink in the light theme, with the state of the message when it is a success
or an error. They dismiss themselves. They never carry the only copy of a message.

### Modals

A scrim with a blur behind it, a card, and three zones: a header that does not move, a
body that scrolls, and a footer that does not move. The focus is trapped inside the
dialog, `Escape` closes it, the page behind it cannot scroll, and the focus returns to
the field that opened it.

---

## 5. The screens

### The home page

The first thing a person sees, and the only page that has to sell anything.

A thin bar at the top with the mark, the links to the features, to the security and to
the sign in. Then the headline, **Identity. Verified.**, the sentence that explains it,
and the two actions: **Get Started** and **Explore Platform**. Then the features in
three cards, the safeguards in a quiet list, and a thin foot.

There is no picture on it. The type, the space and the grey do the work, and nothing is
decorated that does not carry meaning.

### Sign in and sign up

The sign in and the sign up open **over** the page as a dialog, so a reader of the home
page never loses the place they are in. The dialog carries the split of the old sign in
screen: the left half is black with a faint grid and a soft light, and it explains what
the product does. The right half is the form. The two forms swap places inside the
dialog, and closing it leaves the page exactly as it was.

The dialog is wide, the left half keeps only the promise of the product, and under
1080px the left half goes away and the form takes the whole width.

The password field has a reveal button.

The sign out, from the profile or from the account menu, brings the person back to the
home page. The product is what a visitor meets first, and that is what a signed out
person should meet again.

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

- One token file. If a grey is needed and it is not in `tokens.css`, it does not go
  into a component.
- A new animation has to respect `prefers-reduced-motion`.
- A new control needs a hover and a focus state, and needs to survive being rendered
  by a screen reader.
- The phone layout is not optional.
- A table that is too wide for its card scrolls inside the card. The page never scrolls
  sideways.
