# composeApp

The new app, drawn with Compose Multiplatform. One codebase for iPhone, Mac, Windows and Linux.

Right now it shows the feed with made-up data. The look is dark-first and precise: near-black
panels with hairline edges, a lime highlight, facts in Geist Mono, and a timeline rail joining
the updates. There is a matching light theme and a high contrast version of each.

## Run it

```sh
./gradlew :composeApp:run           # desktop window
./gradlew :composeApp:desktopTest   # gesture tests, and PNGs in composeApp/build/screenshots/
```

## Where things are

- `design/`: colours (light, dark, high contrast), type, spacing, shapes, elevation, motion, icons.
- `design/gesture/`: the gesture layer every screen shares.
- `feed/`: the feed screen. Wide windows get sidebar + feed + thread; phones get one column.
- `FakeFeed.kt`: the made-up data. The live feed from `:shared` will replace it.

## Accessibility

- All text is in sp, so it grows with the system text size.
- Every button has a spoken label and a hit area of at least 44pt.
- Level and status always show a word, never colour alone.
- Reduce Motion makes every animation snap. Increase Contrast switches to the high contrast palette.

## Gestures

Each gesture also has a button, a keyboard key and a screen reader action, so none of them is
the only way to do a thing.

| Gesture | Where | Also by |
|---|---|---|
| Swipe a question right / left | Question cards | The choice buttons; Y / N / 1-3 keys; screen reader actions |
| Swipe a reply right to reply | Thread replies | Menu "Reply"; screen reader action |
| Long-press (touch) or right-click (mouse) | Any card or reply | Shift+F10 or the Menu key; screen reader "Show actions" |
| Double-tap to react 👍 | Any card or reply | Menu reactions; screen reader action |
| Pull to refresh | Phone feed | F5 / Cmd+R on desktop; screen reader "Refresh" |
| Tap the top bar or status bar | Phone feed | "Back to top" pill; Home key; screen reader action |
| Swipe in from the left edge | Feed: opens projects. Thread: goes back | Projects and Back buttons; Escape |
| Pinch, double-tap, drag to zoom | Image viewer | Zoom buttons; + / - keys |
| Swipe down to dismiss | Menus and image viewer | Cancel / Close buttons; Escape; tap outside |
| Hold and drag to reorder | Projects in the drawer | Alt+Up / Alt+Down; screen reader "Move up / down" |

Rules: swipes answer to fingers only, so mice keep plain clicks. Sideways swipes ignore touches
that start in the 24pt left edge strip, which belongs to back and the drawer. Nothing listens to
the bottom edge, so the home indicator stays the system's. Crossing a swipe's commit point gives a
haptic tick, and letting go past it gives a firmer one.

Every gesture raises a `FeedEvent`, which is the hook the data layer handles.

Fonts (Geist and Geist Mono) are under the SIL Open Font License; see `fonts-licenses/`.
