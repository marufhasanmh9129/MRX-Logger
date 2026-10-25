# MRX-Logger

**Author:** MARUF  
**License:** MIT

MRX-Logger forwards server console output and player/activity events to Discord. The project is built against the Spigot API and targets **Paper and Spigot 1.18.2 or newer**, with **Java 17 or newer** required to run the plugin. This is not a promise that one build works on every Minecraft version; older server versions use different APIs and Java runtimes.

## Setup

1. Put `MRX-Logger.jar` in the server's `plugins` folder and start the server once.
2. Open `plugins/MRX-Logger/config.yml`. The generated configuration uses the requested top-level sections: `console`, `activities`, `multiple-activities`, and `settings`.
3. Set `DISCORD_TOKEN: "ENTER YOUR BOT TOKEN HERE"` at the top level of `plugins/MRX-Logger/config.yml`. Keep the config private; never share the token.
4. Replace `YOUR_CONSOLE_CHANNEL_ID` and `YOUR_ACTIVITY_CHANNEL_ID` with channel IDs. Enable the bot's Message Content Intent in the Discord Developer Portal and grant it View Channel, Send Messages, and Read Message History permissions as needed.
5. Restart the server or run `/mrxlogger reload`.

## Discord console commands

Any Minecraft console command can be sent from the configured Discord console channel; there is no command allowlist. For security, only Discord server members with the Administrator permission can execute commands, because unrestricted remote console access can grant operator permissions, change the server, or stop it. Type `help` in the console channel for help.

## Connection behavior

JDA handles gateway reconnection. While Discord is offline, messages are skipped and the plugin emits at most one warning per issue instead of repeating an error for every console line. After a successful connection, logging resumes. Missing/invalid token or channel permissions must still be corrected by the server owner.

## Configuration migration

When updating from an older build, the plugin preserves existing non-Discord settings and channel IDs, migrates a legacy `discord.token` value to the top-level `DISCORD_TOKEN` field when possible, and removes the old `discord` and `security` sections so `config.yml` keeps the requested format. Back up your existing config before updating.

## Build

Requires JDK 21 and Gradle 8.14 (the produced plugin bytecode targets Java 17):

```bash
gradle build
```

The JAR is created at `build/libs/MRX-Logger.jar`. The GitHub Actions workflow builds the same artifact on pushes to `main`/`master` or by manual workflow dispatch.

## Release status

Source and configuration are prepared as version 1.2.1. A successful compile and live Paper/Spigot + Discord test are required before describing the artifact as fully tested. Do not publish a build as tested until those checks pass.

## Message placeholders (`messages.yml`)

Templates support reusable placeholders. Common player-context placeholders include `{player}`, `{world}`, `{location}`, `{x}`, `{y}`, `{z}`, `{coordinates}`, and `{time}`. Event-specific placeholders include `{block}`, `{item}`, `{amount}`, `{message}`, `{command}`, `{entity}`, `{victim}`, `{damage}`, `{target}`, and `{advancement}` when the event provides them. For example, `{world}` and `{location}` can be used in `player-join` as well as block messages. Any placeholder unavailable for a particular event is removed automatically instead of appearing as literal `{placeholder}` text. Reload with `/mrxlogger reload` after editing `messages.yml`.
