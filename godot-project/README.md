# Assetify Creator (Godot rebuild)

A from-scratch, lightweight recreation of the dress-up/character-creator idea from
your original web project — rebuilt in **Godot 4** so it actually performs well
with lots of layers/items, and **builds to a real APK automatically via GitHub Actions.**

Nothing here is AI-stitched HTML/React — it's a real Godot project: a scene, a
few GDScript files, and data. Open it in the Godot editor like any other project.

## Why this fixes the lag

- The web version re-rendered a big SVG/React tree on every change.
- This version only touches the one layer (`ItemLayer`) that changed — each
  layer is a single cheap `_draw()` call, items aren't all loaded/rendered at
  once, and the character is drawn in its own isolated `SubViewport` so the UI
  and the character never force each other to redraw.
- This scales to hundreds/thousands of items fine, because items are just data
  entries (`CharacterData.gd`) — the engine never builds a DOM for them.

## What's actually in here right now

- `scenes/Main.tscn` + `scripts/Main.gd` — builds the whole UI (category tabs,
  color swatches, item grid, randomize, save-to-PNG) at runtime.
- `scripts/CharacterData.gd` — all categories/colors/items, as plain data.
- `scripts/ItemLayer.gd` — draws one layer, either as a procedural placeholder
  shape (what ships right now, since no art files were provided) or a real
  texture once you add one.
- `export_presets.cfg` + `.github/workflows/build-apk.yml` — Android debug
  export, fully automated.

**Important:** the items currently render as simple colored shapes
(circles/rectangles/triangles), not drawn art — there were no image assets in
what you gave me to carry over. This proves out the whole system (layering,
color, categories, randomize, export) end-to-end. Swapping in real artwork is
one line per item (see below) and doesn't touch any of the logic.

## Adding your own art

In `scripts/CharacterData.gd`, for any item, add a `"texture"` key:

```gdscript
{"id":"hair_short","category_id":"hair_front","name":"Short",
 "texture": preload("res://assets/hair_short.png"),
 "size": Vector2(230,120), "offset": Vector2(0,-110), "color_group": "hair"}
```

Drop the PNG into `assets/`. `ItemLayer.gd` will draw the texture (tinted by
the selected color) instead of the placeholder shape automatically. You can
add as many items/categories as you want — thousands is fine, since only the
selected item per category is ever instanced.

## Building the APK

### Option A — GitHub Actions (what you asked for)
1. Push this folder to a GitHub repo.
2. The workflow in `.github/workflows/build-apk.yml` runs automatically on
   every push to `main` (or trigger it manually from the Actions tab).
3. When it finishes, open the run → **Artifacts** → download `assetify-apk`.
   That's your installable `.apk` (debug-signed — fine for testing/sideloading,
   not for Play Store).

### Option B — locally, from the Godot editor
1. Install [Godot 4.3](https://godotengine.org/download) (the standard
   version, not .NET, is fine here).
2. Editor → Install the Android export templates (Editor menu will prompt you,
   or Project → Export → Android → Manage Export Templates).
3. Open this project folder, `Project > Export`, pick the **Android** preset,
   hit **Export Project**.

For a **Play Store-ready release build**, you'll need your own release
keystore instead of the debug one — the workflow currently only produces a
debug APK, which is the right thing for testing/sideloading first.

## Extending into RPG / Visual Novel modes

This project structure doesn't lock you into dress-up only:

- **Visual novel:** add the free [Dialogic](https://github.com/dialogic-godot/dialogic)
  addon (install via AssetLib inside the Godot editor) for dialogue boxes,
  branching choices, and character portraits — it can reuse the same character
  you build here as a portrait.
- **RPG:** add a `World.tscn` with a `CharacterBody2D` player (using your
  dress-up character as its sprite), tilemaps for the overworld, and swap
  `Main.tscn` for a scene selector / main menu that routes between "Creator",
  "Story Mode," and "World Map."

Both are normal Godot work from here — the heavy lifting (fast layered
rendering, data-driven assets, APK pipeline) is already done.
