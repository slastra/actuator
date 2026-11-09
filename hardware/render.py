# Regenerate renders/: blender -b -P render.py -- renders .   (run from hardware/)
# The STLs are exported from one Onshape Part Studio, so they keep their
# assembled positions: importing them unmoved gives the assembly.
import bpy, sys, math, mathutils, pathlib
args = sys.argv[sys.argv.index("--") + 1:]
out = pathlib.Path(args[0]); out.mkdir(parents=True, exist_ok=True)
hw = pathlib.Path(args[1])

# name: (colour, exploded offset in mm)
PARTS = {
    "body":          ((0.36, 0.30, 0.74), (0, 0, 0)),
    "door":          ((0.10, 0.10, 0.12), (0, 0, 22)),
    "servo-bracket": ((0.13, 0.06, 0.40), (0, -22, 0)),
    "esp32-bracket": ((0.10, 0.10, 0.12), (-22, 0, 0)),
}
VIEW = mathutils.Vector((-1.0, -1.1, 0.75)).normalized()   # sees the -X and -Y faces

def scene_setup(rx, ry):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.device = "CPU"
    sc.cycles.samples = 128
    sc.cycles.use_denoising = True
    sc.render.film_transparent = True
    sc.render.resolution_x, sc.render.resolution_y = rx, ry
    sc.view_settings.view_transform = "AgX"
    for look in ("AgX - Medium High Contrast", "Medium High Contrast"):
        try:
            sc.view_settings.look = look; break
        except TypeError:
            pass
    w = bpy.data.worlds.new("w"); sc.world = w; w.use_nodes = True
    w.node_tree.nodes["Background"].inputs[0].default_value = (0.85, 0.87, 1.0, 1)
    w.node_tree.nodes["Background"].inputs[1].default_value = 0.35
    for name, d, e, s, shadow in (("key", (-0.5, -0.8, 1.0), 4.0, 0.25, True),
                                  ("fill", (-1.0, 0.2, 0.3), 1.0, 1.0, False),
                                  ("rim", (0.8, 0.9, 0.6), 2.5, 0.6, False)):
        L = bpy.data.objects.new(name, bpy.data.lights.new(name, "SUN"))
        L.data.energy = e; L.data.angle = s; L.visible_shadow = shadow
        L.rotation_euler = (-mathutils.Vector(d).normalized()).to_track_quat("-Z", "Y").to_euler()
        sc.collection.objects.link(L)
    return sc

def material(name, rgb):
    m = bpy.data.materials.new(name); m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1)
    b.inputs["Roughness"].default_value = 0.5
    return m

def load(name, offset=(0, 0, 0)):
    bpy.ops.wm.stl_import(filepath=str(hw / f"{name}.stl"))
    ob = bpy.context.selected_objects[0]
    bpy.context.view_layer.objects.active = ob
    bpy.ops.object.shade_auto_smooth(angle=math.radians(30))
    ob.data.materials.append(material(name, PARTS[name][0]))
    ob.location = offset
    return ob

def bbox(objs):
    bpy.context.view_layer.update()
    pts = [o.matrix_world @ mathutils.Vector(c) for o in objs for c in o.bound_box]
    return (mathutils.Vector([min(p[i] for p in pts) for i in range(3)]),
            mathutils.Vector([max(p[i] for p in pts) for i in range(3)]))

def shoot(sc, objs, path, margin):
    lo, hi = bbox(objs)
    bpy.ops.mesh.primitive_plane_add(size=5000, location=(0, 0, lo.z))
    bpy.context.active_object.is_shadow_catcher = True
    # Frame by projecting the bounding box onto the camera plane.
    rot = (-VIEW).to_track_quat("-Z", "Y")
    inv = rot.inverted()
    corners = [inv @ mathutils.Vector((x, y, z)) for x in (lo.x, hi.x) for y in (lo.y, hi.y) for z in (lo.z, hi.z)]
    w = max(c.x for c in corners) - min(c.x for c in corners)
    h = max(c.y for c in corners) - min(c.y for c in corners)
    aspect = sc.render.resolution_x / sc.render.resolution_y
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    sc.collection.objects.link(cam); sc.camera = cam
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = max(w, h * aspect) * margin
    cam.data.clip_start, cam.data.clip_end = 0.1, 1e5
    cam.location = (lo + hi) / 2 + VIEW * 1000
    cam.rotation_euler = rot.to_euler()
    sc.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)

for name in PARTS:                                   # each part alone
    sc = scene_setup(800, 800)
    shoot(sc, [load(name)], out / f"{name}.png", 1.25)

sc = scene_setup(900, 1200)                          # assembled
shoot(sc, [load(n) for n in PARTS], out / "assembly.png", 1.22)

sc = scene_setup(900, 1200)                          # exploded
shoot(sc, [load(n, PARTS[n][1]) for n in PARTS], out / "exploded.png", 1.15)
