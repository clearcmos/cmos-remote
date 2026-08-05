"""Command output captured from a real Arch host running PipeWire and BlueZ.

MAC addresses are replaced with documentation values; everything else is
verbatim, including the leading tabs bluetoothctl emits. Parser tests assert
against these rather than against invented strings, so a real format change is
what breaks them.
"""

# `wpctl get-volume @DEFAULT_AUDIO_SINK@` (wireplumber 0.5.x)
WPCTL_UNMUTED = "Volume: 0.74\n"
WPCTL_MUTED = "Volume: 0.74 [MUTED]\n"

# `bluetoothctl show` (bluez 5.x), trimmed to the fields the parser reads
BLUETOOTHCTL_SHOW_ON = """Controller AA:BB:CC:DD:EE:FF (public)
\tManufacturer: 0x0002 (2)
\tName: cmos
\tAlias: cmos
\tClass: 0x006c0104 (7078148)
\tPowered: yes
\tPowerState: on
\tDiscoverable: no
"""

BLUETOOTHCTL_SHOW_OFF = BLUETOOTHCTL_SHOW_ON.replace("Powered: yes", "Powered: no").replace(
    "PowerState: on", "PowerState: off"
)

# `bluetoothctl devices Connected`
BLUETOOTHCTL_CONNECTED = "Device 11:22:33:44:55:66 Soundcore Life Q30\n"
