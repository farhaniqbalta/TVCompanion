# WLED configuration and hardware verification

Use stock WLED on an ESP32 and configure the installed 5 V WS2812B strip in WLED first. Set the physical LED count, GPIO, color order, current limiter and a finite realtime timeout in WLED. Start with a short strip and low brightness. Use a correctly sized 5 V supply, common ground, fuses sized for the protected wiring, suitable power injection for longer runs, and a 3.3 V to 5 V data level shifter where needed. Refer to the controller and strip ratings and the [WLED wiring guide](https://kno.wled.ge/basics/wiring-guides/). The TV app's RGB cap does not replace the WLED current limiter or electrical protection.

On the TV, open Ambilight. Enter the controller's private or link-local IPv4 address and edge counts in top/right/bottom/left order. Use zero to skip an edge. Set the corner, direction and zero-based first LED offset to match the installed strip. **Test LEDs** sends a short, brightness-limited pattern with red top, green right, blue bottom and yellow left. Adjust WLED's strip color order if the primary colors are swapped.

The owner counted **60 physical LEDs**. WLED 16.0.1 reported **30 configured LEDs** during the successful sync test. The provisional Ambilight layout was 10 top, 5 right, 10 bottom and 5 left. To use the full strip, check that the supply and WLED current limit suit 60 LEDs, set the output count to 60 in WLED, then enter the actual per-edge counts in Ambilight and run **Test LEDs** at low brightness.

Full perimeter coverage needs about 140 LEDs at the current strip density. The owner plans a 44 top / 26 right / 44 bottom / 26 left layout, which is 140 LEDs and requires 80 more than the installed 60. At full white, 140 LEDs can draw about 8.4 A at 5 V, so the supply, power injection and WLED current limit must be sized before the extension.

Ambilight checks `GET /json/info` for a WLED device and sufficient LEDs. It streams RGB24 using unicast DDP on UDP 4048. The app does not change Wi-Fi, GPIO, segments or current settings. A successful probe confirms HTTP reachability, and UDP sends do not acknowledge visible output.

| Owner-approved hardware trial | Status |
| --- | --- |
| Stock WLED version and configured LED count | PASS for WLED 16.0.1, reporting 30 configured LEDs during the test. GPIO, order and timeout were not checked. |
| Test LEDs on the Skyworth | PARTIAL. WLED reported DDP from the TV and returned to non-realtime mode. Visible colors, orientation and brightness await owner confirmation. |
| Live screen sync on the Skyworth | PASS for basic sync with 30 LEDs configured, confirmed by the owner. Source, duration and color calibration were unspecified. |
| Full 60-LED layout | NOT TESTED. Physical count confirmed by the owner. |
| Start while WLED is off, and start while WLED shows an effect or preset | NOT TESTED |
| Stop → Resume WLED with `{"live":false}` | NOT TESTED |
| Stop → Turn LEDs off with `{"live":false,"on":false}` | NOT TESTED |
| Force-stop, Wi-Fi interruption, controller reboot, external power/brightness change and measured realtime timeout | NOT TESTED |

Resume exits realtime and returns control to WLED's current state. For app/TV crashes or loss of connectivity, the LED controller's configured timeout is the fallback. See [WLED DDP](https://kno.wled.ge/interfaces/ddp/) and the [WLED JSON API](https://kno.wled.ge/interfaces/json-api/).
