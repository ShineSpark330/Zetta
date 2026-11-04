extends Node
# Autoload singleton. Holds all categories / colors / items.
# This mirrors the structure of the original web app (categories -> items -> colors)
# but is plain, cheap-to-render data instead of heavy inline SVG/React state,
# which is what was causing the lag.
#
# TO ADD YOUR OWN ART:
#   Replace the "shape" entries below with "texture": preload("res://assets/your_file.png")
#   in an item dict, and ItemLayer.gd will draw the texture instead of a procedural shape.
#   You can have as many items as you want; they are only instanced when selected,
#   not all at once, so this scales to hundreds/thousands of items without lag.

# Ordered back-to-front. "z" controls draw order.
const CATEGORIES = [
	{"id": "background", "name": "Background", "z": -10},
	{"id": "hair_back", "name": "Hair (Back)", "z": 0},
	{"id": "body", "name": "Body", "z": 5},
	{"id": "ears", "name": "Ears", "z": 6},
	{"id": "clothes", "name": "Clothes", "z": 8},
	{"id": "eyes", "name": "Eyes", "z": 10},
	{"id": "nose", "name": "Nose", "z": 11},
	{"id": "mouth", "name": "Mouth", "z": 12},
	{"id": "hair_front", "name": "Hair (Front)", "z": 20},
	{"id": "accessories", "name": "Accessories", "z": 25},
]

const COLOR_GROUPS = {
	"skin": [
		Color8(255, 224, 189), Color8(241, 194, 125), Color8(224, 172, 105),
		Color8(198, 134, 66), Color8(141, 91, 76), Color8(82, 51, 38),
	],
	"hair": [
		Color8(30, 30, 36), Color8(74, 46, 27), Color8(140, 82, 41),
		Color8(245, 194, 107), Color8(192, 57, 43), Color8(255, 159, 243),
	],
	"eye": [
		Color8(45, 52, 54), Color8(74, 46, 27), Color8(43, 91, 132),
		Color8(62, 142, 65), Color8(142, 68, 173),
	],
	"clothes": [
		Color8(255, 255, 255), Color8(45, 52, 54), Color8(255, 118, 117),
		Color8(116, 185, 255), Color8(85, 230, 193), Color8(108, 92, 231),
	],
	"background": [
		Color8(247, 214, 179), Color8(248, 187, 208), Color8(187, 222, 251),
		Color8(200, 230, 201), Color8(225, 190, 231), Color8(55, 71, 79),
	],
}

# Each item: id, category_id, name, shape (circle/rect/ellipse/rounded_rect/triangle),
# size (Vector2), offset (Vector2 from character center), color_group (key into COLOR_GROUPS or "" for none)
const ITEMS = [
	# background
	{"id":"bg_solid","category_id":"background","name":"Solid","shape":"rect","size":Vector2(720,1280),"offset":Vector2(0,0),"color_group":"background"},
	{"id":"bg_circle","category_id":"background","name":"Circle Backdrop","shape":"circle","size":Vector2(620,620),"offset":Vector2(0,-40),"color_group":"background"},

	# body
	{"id":"body_default","category_id":"body","name":"Default","shape":"rounded_rect","size":Vector2(220,320),"offset":Vector2(0,120),"color_group":"skin"},
	{"id":"body_slim","category_id":"body","name":"Slim","shape":"rounded_rect","size":Vector2(180,320),"offset":Vector2(0,120),"color_group":"skin"},

	# ears
	{"id":"ears_round","category_id":"ears","name":"Round","shape":"circle","size":Vector2(40,40),"offset":Vector2(0,-40),"color_group":"skin"},
	{"id":"ears_elf","category_id":"ears","name":"Elf","shape":"triangle","size":Vector2(40,60),"offset":Vector2(0,-40),"color_group":"skin"},

	# clothes
	{"id":"clothes_tshirt","category_id":"clothes","name":"T-Shirt","shape":"rounded_rect","size":Vector2(240,180),"offset":Vector2(0,150),"color_group":"clothes"},
	{"id":"clothes_dress","category_id":"clothes","name":"Dress","shape":"triangle","size":Vector2(260,260),"offset":Vector2(0,180),"color_group":"clothes"},

	# eyes
	{"id":"eyes_round","category_id":"eyes","name":"Round","shape":"circle","size":Vector2(60,30),"offset":Vector2(0,-20),"color_group":"eye"},
	{"id":"eyes_sharp","category_id":"eyes","name":"Sharp","shape":"ellipse","size":Vector2(70,22),"offset":Vector2(0,-20),"color_group":"eye"},

	# nose
	{"id":"nose_small","category_id":"nose","name":"Small","shape":"triangle","size":Vector2(16,16),"offset":Vector2(0,10),"color_group":"skin"},

	# mouth
	{"id":"mouth_smile","category_id":"mouth","name":"Smile","shape":"ellipse","size":Vector2(50,18),"offset":Vector2(0,40),"color_group":""},
	{"id":"mouth_flat","category_id":"mouth","name":"Flat","shape":"rect","size":Vector2(40,8),"offset":Vector2(0,40),"color_group":""},

	# hair front/back
	{"id":"hair_short","category_id":"hair_front","name":"Short","shape":"rounded_rect","size":Vector2(230,120),"offset":Vector2(0,-110),"color_group":"hair"},
	{"id":"hair_long_back","category_id":"hair_back","name":"Long (Back)","shape":"rounded_rect","size":Vector2(260,360),"offset":Vector2(0,10),"color_group":"hair"},

	# accessories
	{"id":"acc_glasses","category_id":"accessories","name":"Glasses","shape":"rect","size":Vector2(120,24),"offset":Vector2(0,-20),"color_group":""},
	{"id":"acc_none","category_id":"accessories","name":"None","shape":"","size":Vector2.ZERO,"offset":Vector2.ZERO,"color_group":""},
]

func get_items_for_category(category_id: String) -> Array:
	var out := []
	for item in ITEMS:
		if item["category_id"] == category_id:
			out.append(item)
	return out

func get_item(item_id: String):
	for item in ITEMS:
		if item["id"] == item_id:
			return item
	return null
