# PeopleHunt

Paper 1.21.X to 26.X plugin to administer Minecraft manhunt matches and produce after-action reports so you and your friends can relive that moment that David cheated in a diamond axe and killed you.

See https://peoplehunt.pages.dev/ for an example of an after-action report export.

## Features
- Classic compass tracking for hunters
- Highly configurable deathstreaks for hunters if they're really that terrible
- In-depth, detailed after-action reports (including game mode switches to watch your friends cheat)

## Command usage
`/peoplehunt`  
Aliases: `/manhunt`, `/ph`

### Admin subcommands
- `/peoplehunt start` — start the match immediately
- `/peoplehunt stop` — force-stop the match as inconclusive
- `/peoplehunt status` — show current match status
- `/peoplehunt prime [true|false]` — prime the match and wait for runner movement
- `/peoplehunt prepare [health|status|xp|inventory|all]...` — reset participant state before a match
- `/peoplehunt runner [player|selector]` — set or unset the runner
- `/peoplehunt hunter [add|remove|toggle|clear] [player|selector]` — manage explicit hunters
- `/peoplehunt surround <min-radius> [max-radius]` — position hunters around the runner
- `/peoplehunt compass [player|selector]` — give a manhunt compass
- `/peoplehunt kit save <id>` — save your current inventory as a kit and set it active
- `/peoplehunt kit select <id>` — select the active kit
- `/peoplehunt kit clear` — clear the active kit
- `/peoplehunt kit delete <id>` — delete a saved kit
- `/peoplehunt deathstreak reset <all|player>` — reset tracked hunter deathstreaks
- `/peoplehunt aar list` — list after-action reports
- `/peoplehunt aar export [report-uuid]` — export a report, or the latest if omitted
- `/peoplehunt aar flush` — flush buffered AAR path data
- `/peoplehunt rollback <all|player> <duration> [--tp] [--gamemode] [--no-effects]` — roll back recorded player state
- `/peoplehunt settings list` — list live session settings
- `/peoplehunt settings save` — save live session settings to `session-config.yml`
- `/peoplehunt settings reload` — reload `session-config.yml`
- `/peoplehunt settings set <section> <key> <value>` — change a live session setting

### Public commands
- `/compass [player]` — get a manhunt compass
- `/coordinate [overworld|nether] [x] [y] [z]` — convert Overworld/Nether coordinates
- `/peoplehunt portal` — teleport to the runner's last End Portal when a portal prompt is pending
- `/wherewas this <id> [player|selector]` — show a saved coordinate, optionally share it
- `/wherewas remember <id> [x] [y] [z]` — save your current or specified coordinates
- `/wherewas forget <id>` — delete a saved coordinate


