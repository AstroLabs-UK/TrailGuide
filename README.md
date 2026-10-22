# Trail Guide – v0.7.1

## Offline
- **Active journey auto-saved** when a route is calculated
- **Continue guidance offline** (GPS + saved steps/geometry)
- **Offline off-route**: snaps you back toward the nearest point on the saved route (full re-route needs internet)
- **Download map tiles** for ~20 km around you (Settings → Offline maps)
- Restores unfinished journey after app restart

## Free data stack
OpenFreeMap/Mapbox tiles · Nominatim · Photon · Overpass · OSRM · MapLibre offline packs

## Still needed for full offline (any destination, any time)
1. Offline routing engine (GraphHopper / Valhalla mobile graph)
2. Regional OSM extracts (large downloads per country/state)
3. Offline geocoder (e.g. bundled or prebuilt Photon index)
4. Larger tile packs at higher zoom (storage + time)

Build: see BUILD_ON_PC.md
