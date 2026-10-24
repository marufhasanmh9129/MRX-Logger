# MRX-Logger

- Minecraft: Paper 1.21.11
- Java: 21
- Author: MARUF
- License: MIT
- Command: `/mrxlogger reload`

## Build

Use Gradle 8.10+ with JDK 21 and internet access, then run:

```bash
gradle clean jar
```

The output will be `build/libs/MRX-Logger.jar`. The build downloads Paper API and JDA dependencies from their configured repositories.

## Install

1. Build the jar.
2. Copy `build/libs/MRX-Logger.jar` into the server's `plugins` directory.
3. Start the Paper server once, then stop it.
4. Edit `plugins/MRX-Logger/config.yml` and set the Discord bot token and channel IDs.
5. In the Discord Developer Portal, enable **Message Content Intent** for the bot and invite it with View Channel, Send Messages, Read Message History, and Manage Messages permissions in the console channel. Manage Messages is needed to edit the bot's own prior message in some setups.
6. Set `security.allowed-role-ids` to the Discord role IDs allowed to execute console commands. Keep `require-admin-role: true`.
7. Start the server. Use `/mrxlogger reload` to reload YAML settings.

## Important implementation notes

This initial source implementation includes listeners for common events (join/leave, death/respawn, block place/break, player damage, entity spawn/death, item drop/pickup, consume, inventory click, interaction, advancement, player command/chat, and throttled block-position movement). It does not yet capture every possible Minecraft or third-party plugin event. Some configured categories currently act as routing slots for future listeners.

Console capture uses Java logging records. It cannot guarantee capture of every line written directly to stdout/stderr or logs produced by plugins that use custom logging handlers. Console activity filtering is heuristic. The plugin edits only a message ID it previously stored as its own; it does not edit arbitrary messages already in the channel. The first console message is created by the bot.

Discord commands are read only from the configured console channel. With `require-admin-role: true`, only members with a role listed in `allowed-role-ids` can execute commands. Never expose the bot token. The plugin does not log private player data such as IP addresses by default.
