# Offline routing status

## What works offline now
- Active journey is saved when you start navigation
- Turn-by-turn continues with GPS only
- If you leave the route offline, Trail Guide gives a **bearing** back to the nearest point on the saved path (`OfflineRerouter`)
- Map tiles can be pre-downloaded in Settings (~20 km)

## What full GraphHopper offline needs
1. Pre-process OSM data with GraphHopper CLI into a graph folder (per region, often 100MB–2GB+)
2. Host graph packs (e.g. GitHub Releases)
3. App downloads a pack and routes fully offline to any point in that region

That pipeline is separate from the phone app. v0.7.3 improves **offline recovery on the planned route**. City-wide offline routing is the next major offline milestone.
