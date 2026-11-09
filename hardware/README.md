# hardware

Printable parts, exported from Onshape as STL in millimetres. GitHub shows
each one in a 3D viewer when you open the file.

<p align="center">
  <img src="renders/assembly.png" width="300" alt="The assembled actuator: body with the door on top, servo bracket on the side, ESP32 bracket on the front">
  <img src="renders/exploded.png" width="300" alt="Exploded view of the four parts">
</p>

| | Part | Size (mm) | What it is |
|---|---|---|---|
| <img src="renders/body.png" width="110" alt=""> | [`body.stl`](body.stl) | 20 × 25.7 × 81 | Main body |
| <img src="renders/door.png" width="110" alt=""> | [`door.stl`](door.stl) | 20 × 23.1 × 7.5 | Hinged door the horn swings through 90° |
| <img src="renders/servo-bracket.png" width="110" alt=""> | [`servo-bracket.stl`](servo-bracket.stl) | 20 × 2 × 18.5 | U bracket that holds the servo |
| <img src="renders/esp32-bracket.png" width="110" alt=""> | [`esp32-bracket.stl`](esp32-bracket.stl) | 4.2 × 10 × 26 | Bracket that holds the XIAO ESP32C6 |

The STLs keep their positions from the Onshape Part Studio, so importing all
four unmoved gives the assembly. The renders come from `render.py`
(Blender, Cycles): `blender -b -P render.py -- renders .` from this folder.

The horn arm is 6 mm from its pivot, which the firmware's angle mapping
assumes (`firmware/main/servo.h`). If you change the arm length, recalibrate
`SERVO_MIN_US`/`SERVO_MAX_US` (see [firmware/README.md](../firmware/README.md#linkage)).
