# WLED configuration and hardware verification

Use stock WLED on an ESP32 and configure the installed 5 V WS2812B strip in WLED first. Set the physical LED count, GPIO, color order, current limiter and a finite realtime timeout in WLED. Start with a short strip and low brightness. Use a correctly sized 5 V supply, common ground, fuses sized for the protected wiring, suitable power injection for longer runs, and a 3.3 V to 5 V data level shifter where needed. Refer to the controller and strip ratings and the [WLED wiring guide](https://kno.wled.ge/basics/wiring-guides/). The TV app's RGB cap does not replace the WLED current limiter or electrical protection.

On the TV, open Ambilight. Enter the controller's private or link-local IPv4 address and edge counts in top/right/bottom/left order. Use zero to skip an edge. Set the corner, direction and zero-based first LED offset to match the installed strip. **Test LEDs** sends a short, brightness-limited pattern with red top, green right, blue bottom and yellow left. Adjust WLED's strip color order if the primary colors are swapped.

**Installed setup (2026-11-22):** WLED 16.0.1 on GPIO 16 drives **129 LEDs** (WS2812B, GRB order). The Ambilight layout is 40 top / 23 right / 41 bottom / 25 left, starting top left, clockwise, with no skipped LEDs; WLED and the layout must report the same total or sync stops with "Check LED count". The owner confirmed live screen sync on this layout.

Enable gamma for streamed colours: in WLED, **Config → Sync Interfaces → Realtime**, leave **Disable realtime gamma correction** unticked. The app sends raw screen values; without WLED's gamma, dark reds measured 42–50% blue light relative to red on the LEDs against 15–21% on screen and looked pink. Dark scenes appear dimmer with gamma on; raise Brightness in the app if needed.

At full white, 129 LEDs can draw about 7.7 A at 5 V. WLED's current limit scales all channels together, so it dims the strip without shifting colours; set it to suit the supply and wiring. WLED still reported an 850 mA global limit on 2026-11-22.

Ambilight checks `GET /json/info` for a WLED device and sufficient LEDs. It streams RGB24 using unicast DDP on UDP 4048. The app does not change Wi-Fi, GPIO, segments or current settings. A successful probe confirms HTTP reachability, and UDP sends do not acknowledge visible output.

| Owner-approved hardware trial | Status |
| --- | --- |
| Stock WLED version and configured LED count | PASS for WLED 16.0.1 with 129 LEDs on GPIO 16, GRB order. Realtime timeout not checked. |
| Test LEDs on the Skyworth | PARTIAL. WLED reported DDP from the TV and returned to non-realtime mode. Visible colors, orientation and brightness await owner confirmation. |
| Live screen sync on the Skyworth | PASS on the 129-LED layout, confirmed by the owner. Realtime gamma enabled after a red-to-pink mismatch; duration and further colour calibration unspecified. |
| Full-strip layout | PASS for 40/23/41/25 (129 LEDs), confirmed by the owner. |
| Start while WLED is off, and start while WLED shows an effect or preset | NOT TESTED |
| Stop → Resume WLED with `{"live":false}` | NOT TESTED |
| Stop → Turn LEDs off with `{"live":false,"on":false}` | NOT TESTED |
| Force-stop, Wi-Fi interruption, controller reboot, external power/brightness change and measured realtime timeout | NOT TESTED |

Resume exits realtime and returns control to WLED's current state. For app/TV crashes or loss of connectivity, the LED controller's configured timeout is the fallback. See [WLED DDP](https://kno.wled.ge/interfaces/ddp/) and the [WLED JSON API](https://kno.wled.ge/interfaces/json-api/).
