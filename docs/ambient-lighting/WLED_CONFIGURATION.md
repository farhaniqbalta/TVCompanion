# WLED configuration and hardware verification

Use stock WLED on an ESP32 and configure the installed 5 V WS2812B strip in WLED first. Set the physical LED count, GPIO, color order, current limiter and a finite realtime timeout in WLED. Start with a short strip and low brightness. Use a correctly sized 5 V supply, common ground, fuses sized for the protected wiring, suitable power injection for longer runs, and a 3.3 V to 5 V data level shifter where needed. Refer to the controller and strip ratings and the [WLED wiring guide](https://kno.wled.ge/basics/wiring-guides/). The TV app's RGB cap does not replace the WLED current limiter or electrical protection.

On the TV, open Ambilight. Enter the controller's private or link-local IPv4 address and edge counts in top/right/bottom/left order. Use zero to skip an edge. Set the corner, direction and zero-based first LED offset to match the installed strip. **Test LEDs** sends a short, brightness-limited pattern with red top, green right, blue bottom and yellow left. Adjust WLED's strip color order if the primary colors are swapped.

Ambilight checks `GET /json/info` for a WLED device and sufficient LEDs. It streams RGB24 using unicast DDP on UDP 4048. The app does not change Wi-Fi, GPIO, segments or current settings. A successful probe confirms HTTP reachability, and UDP sends do not acknowledge visible output.

| Owner-approved hardware trial | Status |
| --- | --- |
| Stock WLED firmware version, strip/GPIO/order, segment and timeout configuration | NOT TESTED |
| Physical color test, orientation, short/long strips, brightness and 30 FPS | NOT TESTED |
| Start while WLED is off, and start while WLED shows an effect or preset | NOT TESTED |
| Stop → Resume WLED with `{"live":false}` | NOT TESTED |
| Stop → Turn LEDs off with `{"live":false,"on":false}` | NOT TESTED |
| Force-stop, Wi-Fi interruption, controller reboot, external power/brightness change and measured realtime timeout | NOT TESTED |

Resume exits realtime and returns control to WLED's current state. For app/TV crashes or loss of connectivity, the LED controller's configured timeout is the fallback. See [WLED DDP](https://kno.wled.ge/interfaces/ddp/) and the [WLED JSON API](https://kno.wled.ge/interfaces/json-api/).
