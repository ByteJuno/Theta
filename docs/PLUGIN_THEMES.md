# Data plugin themes

A confirmed data ZIP can contain `themes/<id>.json`. Themes are declarative data;
they do not execute code, open URLs, or register SDK extensions. Enable the data
plugin, then select its theme in **Settings → Appearance and theme**. The palette
selection is exclusive with Material You and Theta brand colors; light/dark/system
mode remains independent. Disabling, uninstalling, quarantining, or removing the
selected theme restores Theta brand colors and clears the unavailable selection.
Re-enabling the plugin does not silently reselect its theme.

```json
{
  "id": "ocean",
  "displayName": "Ocean 海洋",
  "useDynamicColor": false,
  "light": {
    "background": "#FFFFFF",
    "surface": "#FFFFFF",
    "assistantText": "#101030",
    "userBubbleContainer": "#EEEEFF",
    "onUserBubble": "#151550",
    "capsuleContainer": "#EEEEFF",
    "onCapsule": "#202040"
  },
  "dark": {
    "assistantText": "#FFFFFF",
    "capsuleContainer": "#202030",
    "onCapsule": "#FFFFFF"
  },
  "cornerScale": 1.0,
  "messageStyle": "editorial"
}
```

`id` must match the filename (1–64 ASCII letters, digits, `_`, `-`; first character
alphanumeric). `displayName` is required, at most 80 UTF-16 units, without control
characters, bidi overrides/isolates, or unpaired surrogates. Themes are identified
by plugin ID plus theme ID, so separate plugins can reuse the same theme ID.

All other fields are optional. Defaults are `useDynamicColor: false`, empty color
overrides, `cornerScale: 1`, and `messageStyle: "editorial"`. Corner scale is a
finite number in `0.5..1.5`, affecting Material shapes and message/chip corners.
`editorial` keeps the open assistant reading flow, `bubble` adds an assistant
container, and `compact` reduces message spacing without shrinking touch targets.
Saved messages, streaming text, and retained replies use the same presentation.

Allowed `light`/`dark` color keys:

- `background`, `surface`, `onBackground`, `onSurface`, `onSurfaceVariant`
- `surfaceContainerLowest`, `surfaceContainerLow`, `surfaceContainer`,
  `surfaceContainerHigh`, `surfaceContainerHighest`
- `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`
- `userBubbleContainer`, `onUserBubble`, `assistantText`
- `capsuleContainer`, `onCapsule` (reasoning and collapsed tool chips)

Colors must be opaque `#RRGGBB` strings. Unknown fields, duplicate JSON keys,
invalid types, and all error/safety color overrides are rejected. Files are limited
to 16 KiB, in addition to the existing archive limits and path checks. One invalid
theme is reported in plugin diagnostics and excluded; other themes, skills, and
personas in the archive remain available. Archive corruption or identity failure
still quarantines the entire package.

The host checks WCAG sRGB relative luminance contrast of at least **4.5:1** for
text/surface combinations, primary links/buttons, bubble text, capsule text, and
host error text against overridden surfaces. Both light and dark variants must
pass against the brand fallback. Dynamic themes are additionally checked against
both actual wallpaper palettes at composition time; an incompatible theme is not
selectable and an active incompatible theme falls back to the brand palette.
The host retains all `error`, `onError`, `errorContainer`, and `onErrorContainer`
roles used by full-auto warnings and expanded tool approval/recovery cards.
