# Server
This will serv worlds and dungeons for the RaidSimulator.
We are using this server from start in case we wanne go MMO or at least hosting party-raids one day.


# User created Content
With this technology we are able to deploy user generated content made with the RaidBuilder.
The Dungeons come in SML-Text format and we be backed into binary chunks.
These chunks are streamed to the client if player moves around or enters/loadsl a dungeon.

# Technology
KTOR, Kotlin

## Local Maven Setup
The Kotlin SML parser is located in `sml-parser/` and should be published to Maven Local:
```sh
cd sml-parser
./gradlew jvmTest publishToMavenLocal
```

The SMS scripting engine is located in `sms/` and should also be published:
```sh
cd sms
./gradlew publishToMavenLocal
```

## Run Server
```sh
cd Server
./gradlew run
```

## Dungeon Source
The server expects `dungeon.sml` in the **Server** working directory (same folder as this README).

## Logging
Logs are written to:
- `Server/logs/server.log` (rolling daily logs)

## Chunk Endpoint
`GET /chunk?x=0&y=0&z=0` returns binary chunk data:
- Header: chunkX, chunkY, chunkZ (int32), blockCount (uint16), blockSizeCm (uint16)
- Blocks: per-block 4 bytes (x, y, z, tileId) — positions are local to chunk (0..31)

## SMS Test
On server start, a small SMS test script runs and logs:
- `SMS test: boss HP after hit = 83`
- `SMS test result: 83`