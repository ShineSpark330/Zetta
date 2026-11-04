extends Control
# Builds the entire UI in code so you don't need to hand-wire a scene tree.
# Swap in a real .tscn layout later if you want a custom visual design -
# nothing here is required to stay code-built.

const STAGE_SIZE := Vector2(720, 900)

var layers: Dictionary = {}       # category_id -> ItemLayer
var selections: Dictionary = {}   # category_id -> {"item_id": String, "color_index": int}
var current_category: String = ""

var sub_viewport: SubViewport
var character_root: Node2D
var color_row: HBoxContainer
var item_grid: GridContainer
var category_row: HBoxContainer
var tab_buttons: Dictionary = {}

func _ready() -> void:
	_build_stage()
	_build_panel()
	for cat in CharacterData.CATEGORIES:
		var items = CharacterData.get_items_for_category(cat["id"])
		var start_item = items[0] if items.size() > 0 else null
		selections[cat["id"]] = {
			"item_id": (start_item["id"] if start_item else ""),
			"color_index": 0,
		}
		_update_layer(cat["id"])
	_select_category(CharacterData.CATEGORIES[0]["id"])

# ---------- Character stage (isolated viewport so export is clean) ----------

func _build_stage() -> void:
	var stage_container := SubViewportContainer.new()
	stage_container.name = "StageContainer"
	stage_container.stretch = true
	stage_container.anchor_left = 0
	stage_container.anchor_right = 1
	stage_container.anchor_top = 0
	stage_container.anchor_bottom = 0.6
	add_child(stage_container)

	sub_viewport = SubViewport.new()
	sub_viewport.size = STAGE_SIZE
	sub_viewport.transparent_bg = true
	sub_viewport.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	stage_container.add_child(sub_viewport)

	character_root = Node2D.new()
	character_root.name = "CharacterRoot"
	character_root.position = STAGE_SIZE / 2.0
	sub_viewport.add_child(character_root)

	for cat in CharacterData.CATEGORIES:
		var layer := ItemLayer.new()
		layer.name = cat["id"]
		layer.z_index = cat["z"]
		character_root.add_child(layer)
		layers[cat["id"]] = layer

# ---------- Bottom control panel ----------

func _build_panel() -> void:
	var panel := VBoxContainer.new()
	panel.name = "Panel"
	panel.anchor_left = 0
	panel.anchor_right = 1
	panel.anchor_top = 0.6
	panel.anchor_bottom = 1
	panel.add_theme_constant_override("separation", 8)
	add_child(panel)

	# Category tabs
	var cat_scroll := ScrollContainer.new()
	cat_scroll.custom_minimum_size = Vector2(0, 56)
	cat_scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_AUTO
	cat_scroll.vertical_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	panel.add_child(cat_scroll)

	category_row = HBoxContainer.new()
	cat_scroll.add_child(category_row)
	for cat in CharacterData.CATEGORIES:
		var btn := Button.new()
		btn.text = cat["name"]
		btn.toggle_mode = true
		btn.pressed.connect(_select_category.bind(cat["id"]))
		category_row.add_child(btn)
		tab_buttons[cat["id"]] = btn

	# Color swatches
	var color_scroll := ScrollContainer.new()
	color_scroll.custom_minimum_size = Vector2(0, 56)
	color_scroll.vertical_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	panel.add_child(color_scroll)
	color_row = HBoxContainer.new()
	color_scroll.add_child(color_row)

	# Item grid
	var item_scroll := ScrollContainer.new()
	item_scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	panel.add_child(item_scroll)
	item_grid = GridContainer.new()
	item_grid.columns = 3
	item_scroll.add_child(item_grid)

	# Action buttons
	var action_row := HBoxContainer.new()
	action_row.add_theme_constant_override("separation", 12)
	panel.add_child(action_row)

	var randomize_btn := Button.new()
	randomize_btn.text = "🎲 Randomize"
	randomize_btn.pressed.connect(_on_randomize)
	action_row.add_child(randomize_btn)

	var save_btn := Button.new()
	save_btn.text = "💾 Save PNG"
	save_btn.pressed.connect(_on_save_png)
	action_row.add_child(save_btn)

# ---------- Selection logic ----------

func _select_category(cat_id: String) -> void:
	current_category = cat_id
	for id in tab_buttons:
		tab_buttons[id].button_pressed = (id == cat_id)
	_refresh_color_row()
	_refresh_item_grid()

func _refresh_color_row() -> void:
	for c in color_row.get_children():
		c.queue_free()
	var items = CharacterData.get_items_for_category(current_category)
	if items.is_empty():
		return
	var group_key = items[0]["color_group"]
	if group_key == "" or not CharacterData.COLOR_GROUPS.has(group_key):
		return
	var colors = CharacterData.COLOR_GROUPS[group_key]
	for i in range(colors.size()):
		var swatch := ColorRect.new()
		swatch.color = colors[i]
		swatch.custom_minimum_size = Vector2(40, 40)
		var btn := Button.new()
		btn.custom_minimum_size = Vector2(44, 44)
		btn.add_child(swatch)
		btn.pressed.connect(_on_color_selected.bind(i))
		color_row.add_child(btn)

func _refresh_item_grid() -> void:
	for c in item_grid.get_children():
		c.queue_free()
	var items = CharacterData.get_items_for_category(current_category)
	for item in items:
		var btn := Button.new()
		btn.text = item["name"]
		btn.custom_minimum_size = Vector2(220, 64)
		btn.pressed.connect(_on_item_selected.bind(item["id"]))
		item_grid.add_child(btn)

func _on_color_selected(index: int) -> void:
	selections[current_category]["color_index"] = index
	_update_layer(current_category)

func _on_item_selected(item_id: String) -> void:
	selections[current_category]["item_id"] = item_id
	_update_layer(current_category)

func _update_layer(cat_id: String) -> void:
	var sel = selections[cat_id]
	var layer: ItemLayer = layers[cat_id]
	var item = CharacterData.get_item(sel["item_id"])
	if item == null or item["shape"] == "":
		layer.visible = false
		return
	layer.visible = true
	var color := Color.WHITE
	var group_key = item["color_group"]
	if group_key != "" and CharacterData.COLOR_GROUPS.has(group_key):
		var colors = CharacterData.COLOR_GROUPS[group_key]
		var idx = clamp(sel["color_index"], 0, colors.size() - 1)
		color = colors[idx]
	layer.set_item(item, color)

# ---------- Randomize / Export ----------

func _on_randomize() -> void:
	for cat in CharacterData.CATEGORIES:
		var cat_id = cat["id"]
		var items = CharacterData.get_items_for_category(cat_id)
		if items.is_empty():
			continue
		var pick = items[randi() % items.size()]
		var color_count := 1
		if pick["color_group"] != "" and CharacterData.COLOR_GROUPS.has(pick["color_group"]):
			color_count = CharacterData.COLOR_GROUPS[pick["color_group"]].size()
		selections[cat_id] = {
			"item_id": pick["id"],
			"color_index": randi() % color_count,
		}
		_update_layer(cat_id)
	_refresh_color_row()
	_refresh_item_grid()

func _on_save_png() -> void:
	await RenderingServer.frame_post_draw
	var img := sub_viewport.get_texture().get_image()
	var path := "user://character_%d.png" % Time.get_unix_time_from_system()
	img.save_png(path)
	print("Saved: ", path)
