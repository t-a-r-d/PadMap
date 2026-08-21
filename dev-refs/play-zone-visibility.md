# Per-layer in-game control visibility

PadMap normally keeps mapped touch zones invisible while a game is playing. A
layer can opt into a visual-only preview for situations such as a menu or an
unfamiliar secondary layout.

The preview is owned by `OverlayManager`, uses an accessibility overlay marked
`FLAG_NOT_TOUCHABLE`, and is removed whenever PadMap leaves a game or enters
configuration. It cannot consume game taps or controller input. The active
layer is the sole source of the preview contents, so switching layers also
switches (or removes) the preview atomically with PadMap's existing held-touch
release.

Each `LayerBind` owns `showZonesInGame`; this makes visibility a property of a
specific game layer rather than a global setting. Existing saved layouts decode
with the default `false` value.
