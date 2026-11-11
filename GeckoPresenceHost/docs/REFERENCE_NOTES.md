# Reference Notes

The Android host architecture is informed by:

- The user's supplied Metrolist `DiscordRpcManager.kt` reference, commit `30627a4986fc188aaa96070f56cfada499be11c2`.
- Discord's official Social SDK Android Direct Rich Presence setup and mobile requirements.
- Discord's official Social SDK C++ callback loop and Rich Presence documentation.

The project does not bundle Metrolist code, Metrolist assets, Discord SDK binaries, Discord logos, or any user token. The native adapter is written around the documented `discordpp::Client` surface.
