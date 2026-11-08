![CoreProtect](https://userfolio.com/uploads/coreprotect-banner-v19.png)

[![Artistic License 2.0](https://img.shields.io/github/license/PlayPro/CoreProtect?&logo=github)](https://github.com/PlayPro/CoreProtect/blob/master/LICENSE)
[![GitHub Workflows](https://github.com/PlayPro/CoreProtect/actions/workflows/build.yml/badge.svg)](https://github.com/PlayPro/CoreProtect/actions)
[![Netlify Status](https://img.shields.io/netlify/c1d26a0f-65c5-4e4b-95d7-e08af671ab67)](https://app.netlify.com/sites/coreprotect/deploys)
[![CodeFactor](https://www.codefactor.io/repository/github/playpro/coreprotect/badge)](https://www.codefactor.io/repository/github/playpro/coreprotect)
[![Join us on Discord](https://img.shields.io/discord/348680641560313868.svg?label=&logo=discord&logoColor=ffffff&color=7389D8&labelColor=6A7EC2)](https://discord.gg/b4DZ4jy)

# CoreProtect

CoreProtect is a fast, efficient data logging and anti-griefing tool. Inspect, lookup, rollback, and restore any amount of damage without impacting your server performance.

CoreProtect is the [#1 anti-griefing plugin](https://bstats.org/plugin/bukkit/CoreProtect), and has been actively developed since early 2012.

## About this fork

This is the Kaizen Network fork of [PlayPro/CoreProtect](https://github.com/PlayPro/CoreProtect). It is not an official CoreProtect release; for CoreProtect itself, use the upstream links below.

**Before you install: there is no way back to official CoreProtect without a backup.**

- On its first start the fork upgrades the database schema to version 25.1. Official CoreProtect 24.x then refuses to start on that database ("CoreProtect v25.0 or higher is required"). This happens even if you never use the features below. Back up the database while the server is stopped, before the first start; going back means restoring that backup and losing everything logged since.
- Rows stored with `compress-metadata` on can only be read by builds that have this code.
- The fork uses DuckDB 1.5.6.0, upstream 1.4.5.0. A DuckDB file opened and took writes in both versions in a test, so this alone does not block going back, but 1.4.5 brings back the checkpoint memory problem described below.

It is built on upstream master and differs from it in these ways:

- `co migrate-duckdb` moves a running server from SQLite to DuckDB without a restart: a background copy, a verify, then a short logging pause for the switch (0.2 to 1.3 s in the production runs). Failures go back to SQLite; the guide covers the rare case where that also fails. See [docs/duckdb-migration.md](docs/duckdb-migration.md).
- `co migrate-duckdb compact` rewrites a live DuckDB file the same way, to drop free space. With `compress-metadata` on, it also compresses stored item metadata.
- `compress-metadata` (config, default `false`) stores container, entity container and item metadata as zlib with a preset dictionary. It is lossless, but one-way: builds without this code cannot read compressed rows.
- DuckDB JDBC 1.5.6.0 instead of 1.4.5.0, which could run out of memory in a checkpoint when a value was larger than one 128 KiB block.
- Version 25.1, so the v25 schema patches run on upgraded databases. The upgrade is one-way: CoreProtect 24.x refuses the database afterwards.
- SQLite temp files go to `plugins/CoreProtect/tmp`, so index builds work where `/tmp` is a small tmpfs (as in Pterodactyl containers). The setting applies to the whole server process, so other plugins' SQLite temp files go there too.
- The config file owner is only set when it differs, which fixes writing `config.yml` on Windows.
- With `-Dcpbench=true`, timing lines for lookups, rollbacks, purges and the DuckDB spatial index. The benchmark and migration test tools in [lab/](lab/README.md) use them; results are in [lab/RESULTS.md](lab/RESULTS.md).

Build with JDK 25: `mvn clean package -Dproject.branch=fork`, which gives `target/CoreProtect-25.1.jar`. An empty branch name refuses to start, and a name containing `-dev` skips the schema patches.

| Quick Links |  |
| --- | --- |
| CoreProtect Discord: | [discord.gg/b4DZ4jy](https://discord.gg/b4DZ4jy) |
| CoreProtect Patreon: | [patreon.com/coreprotect](https://www.patreon.com/coreprotect) |
| CoreProtect Documentation: | [docs.coreprotect.net](https://docs.coreprotect.net) |
| Downloads for MC 1.14 - 26.2: | [coreprotect.net/latest](https://coreprotect.net/latest/) |
| Downloads for MC 1.8 - 1.12: | [coreprotect.net/legacy](https://coreprotect.net/legacy/) |
| Downloads for Fabric: | [coreprotect.net/fabric](https://coreprotect.net/fabric) |
| Downloads for Hytale: | [coreprotect.net/hytale](https://coreprotect.net/hytale) |

## API

### [API Documentation](https://docs.coreprotect.net/api/)

### Dependency Information

Maven:

```xml
<repository>
    <id>playpro-repo</id>
    <url>https://maven.playpro.com</url>
</repository>
```

```xml
<dependency>
    <groupId>net.coreprotect</groupId>
    <artifactId>coreprotect</artifactId>
    <version>24.1</version>
    <scope>provided</scope>
</dependency>
```

## Donation Keys

To support the project and obtain a donation key, visit [coreprotect.net/donate](https://coreprotect.net/donate/).

## Other Plugins

* DarkerNights: [spigotmc.org/resources/darkernights.87814](https://www.spigotmc.org/resources/darkernights.87814/)
* TransitTubes: [patreon.com/posts/75731668](https://www.patreon.com/posts/75731668)

## Video Tutorial

[![CoreProtect video tutorial](https://www.userfolio.com/uploads/coreprotect-video-tutorial.png)](https://youtu.be/JwijCiueZ3Y)

## Core Features

* Fast, efficient data logging.
* Rollbacks and restores with lag-free processing.
* Multi-threaded to ensure server performance is never impacted.
* No configuration required. Install the plugin, and you're good to go.
* Embedded DuckDB columnar storage by default, with optional ClickHouse, SQLite, and MySQL support.
* Supports Bukkit, Spigot, Paper, Folia, MultiPaper, and more.
* Permission system support and advanced lookup permissions.
* Easy-to-use commands and inspector.
* Advanced lookup filters and operation limits by radius, world, or WorldEdit selection.
* Paginated results with clickable pagination.
* Preview rollbacks and restores, and undo rollbacks by restoring the same data.
* Rollback or restore specific users, multiple users, or global activity.
* Inventory rollback support.
* Purge old data by time, world, and block type.
* Multi-world support.
* Configurable global and per-world logging settings.
* Safe default parameters.
* Include or exclude specific blocks, items, entities, or users, plus logging blacklists.
* WorldEdit and supported FAWE logging.
* Enhanced item lookup tooltips.
* Localization and translation support.
* Automatic update checking and error reporting.
* Developer and networking API support.
* *...and much more!*

## What does it log?

### Blocks and World Changes

* Blocks placed or broken by players.
* Natural block breakage, such as attached blocks breaking when their support is removed.
* Block movement, including falling sand, gravel, and piston movement.
* Water and lava placement, removal, and flow.
* Liquid tracking, associating flowing liquids with the player who placed the source.
* Fire ignition from flint and steel or fire charges, block burning, and fire fade.
* Optional natural fire extinguishing.
* TNT, Creeper, Ghast, and other explosions, plus primed TNT removal.
* Entities changing blocks, including Endermen, Ender Dragons, and zombies breaking doors.
* Leaf decay.
* Tree growth linked back to the player who planted the sapling.
* Mushroom, vine, amethyst, chorus, and bamboo growth.
* Sculk spread from sculk catalysts.
* Nether portal and other natural portal generation.
* Crops trampled by players and turtle eggs trampled by players or entities.
* Snow generated by snow golems.
* Liquid-formed blocks such as obsidian, cobblestone, and concrete.
* Flowers placed in flower pots.
* Dragon egg teleportation.
* Suspicious sand and gravel brushing or destruction.
* Custom blocks, such as CraftEngine blocks.

### Containers, Inventories, and Items

* Items taken from or placed into vanilla or supported third-party containers.
* Hopper, dropper, and dispenser transactions, including container transfers.
* Player inventory transactions, including items dropped, thrown, shot, or picked up.
* Items broken, created, destroyed, deposited, or withdrawn by players.
* Items placed on campfires and lectern book transactions.
* Chiseled bookshelf, decorated pot, and shelf inventory transactions.
* Copper Golem chest and jukebox transactions, plus crafter slot changes.
* Bundle actions through the `#bundle` tag.
* Items crafted or traded with villagers.
* Allay item exchanges.
* Paintings and item frames.

### Entities and Player Activity

* Entity deaths and detailed death reasons.
* Player-caused entity kills, including animals and monsters.
* Armor stands and End Crystals.
* Villagers killed by lightning, gossip data, and job-site memories.
* Thrown eggs, snowballs, potions, ender pearls, tridents, and wind charges.
* Player interactions with doors, buttons, levers, containers, bookshelves, and jukeboxes.
* Sign text, color, glow, wax, and modern double-sided signs.
* Player chat messages and commands.
* Player sessions including logins and logouts, player deaths, and username changes.
* Skull skin texture data on Paper servers and custom skull textures.
* WorldEdit changes and supported FAWE clipboard pastes.
* *...and more!*

## How to use the inspector

Once you have the inspector enabled with `/core inspect` or `/co i`, you can do the following:

* Left-click a block to see who placed that block.
* Right-click a block to see what adjacent block was removed.
* Right-click while placing a block in a location to see what block was removed at that location.
* Right-click while placing a block in another block to see who placed it. For example, place dirt in water to see who placed the water.
* Right-click a door, button, lever, container, or similar block to see who last used it.

## A Few Reviews

* *"It's easy to use and lightning fast when it comes to rollbacks."*
* *"I've been running a MC server since before Bukkit, so I've used just about every block logger out there. CoreProtect is the best and the fastest."*
* *"Go with CoreProtect. It's fast, updated frequently, and logs WorldEdit."*
* *"Overall it felt more natural, simpler, and much faster to use CoreProtect than Prism, which just doesn't compare."*
* *"CoreProtect brings complex logging and rollbacks, whilst at the same time keeping a low profile."*
* *Featured on [Linus Tech Tips](https://www.userfolio.com/uploads/coreprotect-linus.png).*

## Useful Links

**Documentation**  
https://docs.coreprotect.net

**Commands**  
https://docs.coreprotect.net/commands/

**Permissions**  
https://docs.coreprotect.net/permissions/

**API Documentation**  
https://docs.coreprotect.net/api/

**Feedback & Support**  
https://github.com/PlayPro/CoreProtect/issues

**Discord**  
Join us on Discord: https://discord.gg/b4DZ4jy

**Donate**  
Enjoy using CoreProtect and want to show your support? [Join our Patreon!](https://www.patreon.com/coreprotect)

**Sponsors**  
Thanks to [HostHorde](https://www.hosthorde.com) for sponsoring CoreProtect!

## Bug Reports

For any bug reports, please submit a ticket here:  
https://github.com/PlayPro/CoreProtect/issues

Thanks for your support!

## bStats

[![bStats Graph Data](https://bstats.org/signatures/bukkit/CoreProtect.svg)](https://bstats.org/plugin/bukkit/CoreProtect)

## Contributing

CoreProtect is an open source project, and gladly accepts community contributions.

If you'd like to contribute, please read our contributing guidelines here: [CONTRIBUTING.md](CONTRIBUTING.md)

[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.0-4baaaa.svg)](CONTRIBUTING.md#code-of-conduct)
