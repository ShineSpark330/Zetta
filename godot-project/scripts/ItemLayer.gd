extends Node2D
class_name ItemLayer
# Draws a single dress-up layer. Cheap: one _draw() call, no DOM, no re-render
# of unrelated layers when something else changes - this is the core fix for
# the lag in the original web version.

var shape: String = ""
var shape_size: Vector2 = Vector2.ZERO
var tint: Color = Color.WHITE
var tex: Texture2D = null

func set_item(item: Dictionary, color: Color) -> void:
	shape = item.get("shape", "")
	shape_size = item.get("size", Vector2.ZERO)
	tex = item.get("texture", null)
	tint = color
	position = item.get("offset", Vector2.ZERO)
	queue_redraw()

func _draw() -> void:
	if tex:
		draw_texture_rect(tex, Rect2(-shape_size / 2.0, shape_size), false, tint)
		return
	match shape:
		"rect", "rounded_rect":
			draw_rect(Rect2(-shape_size / 2.0, shape_size), tint, true)
		"circle":
			draw_circle(Vector2.ZERO, shape_size.x / 2.0, tint)
		"ellipse":
			var points := PackedVector2Array()
			var segs := 32
			for i in range(segs):
				var a := TAU * i / segs
				points.append(Vector2(cos(a) * shape_size.x / 2.0, sin(a) * shape_size.y / 2.0))
			draw_colored_polygon(points, tint)
		"triangle":
			var p := PackedVector2Array([
				Vector2(0, -shape_size.y / 2.0),
				Vector2(shape_size.x / 2.0, shape_size.y / 2.0),
				Vector2(-shape_size.x / 2.0, shape_size.y / 2.0),
			])
			draw_colored_polygon(p, tint)
		_:
			pass
