# Road Ahead

A Karoo extension with one ride field, **Road ahead**: the elevation profile of the road in front
of you, with no route loaded. It guesses the road from the one you're on, as Wahoo's Summit does.
It runs entirely on the Karoo: no phone, no server, no account.

## How it works

1. **Find the road** (`core/.../RoadSnap.kt`). Each GPS fix snaps to the nearest mapped road or
   path that runs the way you're heading. A footpath or driveway loses to a road beside it.
2. **Follow the road** (`core/.../RoadFollow.kt`). At the end of a mapped piece it carries on
   along the piece with the same name, otherwise the one that runs straightest (within 30
   degrees), otherwise round a bend where nothing else meets the road. Anything else is a
   junction, and the prediction stops there. A roundabout takes the exit with your road's name,
   otherwise the one that best carries on the way you came in.
3. **Read the heights** (`core/.../Heights.kt`, `Profile.kt`). It samples a 30 m elevation grid
   every 25 m along that road, smooths it, and draws a straight line across bridges and tunnels.
4. **Keep it going** (`core/.../Tracker.kt`). The prediction holds while you stay on it, and a new
   one is worked out when you leave it. Nothing is worked out while you're stopped.

Every number those rules turn on is in `core/.../Rules.kt`.

## Layout

- `core/` is the engine. Plain Kotlin, no Android, so it runs and tests on the Mac. It also
  holds the reader for the Karoo's map files (`MapRoads.kt`) and the drawing (`Picture.kt`), which
  decides every shape and colour so the Karoo and the Mac draw the same picture.
- `app/` is the Karoo extension: the field, the Wi-Fi elevation fetch and the settings screen.
- `replay/` is a Mac-only tool that runs a recorded ride through the engine and scores it.
- `fixtures/` and `out/` are not in git: test data and the replay tool's output.

## Build and test

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew --no-daemon :core:test
./gradlew --no-daemon :replay:installDist
./gradlew --no-daemon :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## On the Karoo

1. Open **Road Ahead** and tap **Allow map access**. The Karoo keeps its offline maps in
   `/sdcard/offline/maps`, a folder only the storage permission opens. The app targets Android
   API 28 so that the ordinary prompt can grant it. If the prompt doesn't appear:
   `adb shell pm grant au.com.fjellconsulting.roadahead android.permission.WRITE_EXTERNAL_STORAGE`
   and the same for `READ_EXTERNAL_STORAGE`.
2. Leave the Karoo on Wi-Fi. It fetches the elevation file for where it is, and for anywhere
   within the distance set on the screen (30 km to start with). One file is about 12 MB to fetch.
3. Add the **Road ahead** field to a ride page, under ROAD AHEAD. Tap it to step through the
   distances.

The app's screen shows the field as it looks right now, what it has found, and every setting:
the distances a tap steps through, the colours, the numbers on the field, how far around to fetch
elevation, and the files held. Units follow the Karoo's rider profile.

### Check it at the desk

```
adb shell am start -n au.com.fjellconsulting.roadahead/.SettingsActivity --ez demo true --ef lat -35.3272 --ef lng 149.0192 --ef course 330   # put it on Mount Stromlo Road for a minute
adb shell am start -n au.com.fjellconsulting.roadahead/.SettingsActivity --ez fetch true   # fetch elevation files now
adb logcat -s RoadAhead                                                                     # maps opened, files fetched, each new prediction
```

## Check a map file

```
./replay/build/install/replay/bin/replay map fixtures/some.map -35.3272 149.0192 330
```

It prints the file's version and zoom levels, which tags its roads carry, whether pieces stop at
the map tile's edge, how many roundabouts it finds, and where the engine goes from that point. A
`.map` file also works as the road file of a replay, in place of the text extract. That is the
check that the Karoo's own maps give the same scores as the OpenStreetMap extract.

## Replay a ride

```
./replay/build/install/replay/bin/replay fixtures/roads-act.tsv fixtures fixtures/ride.csv
```

Arguments: the road file, the folder that holds the `.hgt` elevation files, and the ride. Options:

- `--want 5000` asks for 5 km ahead in place of 2 km.
- `--cut` chops the roads into map tiles first, the way the Karoo's map files hand them over.
  The scores must not change.
- `--trace` lists every new prediction and every miss.
- `--png 58` draws what the field shows at fix 58, at four field sizes, to `out/field-58.png`.
- `--dump 319` writes the predicted road and the ride after fix 319 to `out/dump-319.txt`.
- `--moments out/m.json` writes a sample of what the field would have shown.

It reports how often the picture was the road then ridden, who turned when it wasn't, and how far
the predicted heights sat from the ride's own altitude.

## Test data

All of it lives in `fixtures/`, which git ignores.

- **Elevation:** `S36E149.hgt`, from Terrain Tiles on AWS Open Data.
  `curl -O https://elevation-tiles-prod.s3.amazonaws.com/skadi/S36/S36E149.hgt.gz`, then `gunzip`.
- **Roads:** `roads-act.tsv`, made from an Overpass API query for every `highway` way in
  south Canberra (`-35.48,148.96,-35.22,149.20`, `out tags geom`). One road a line: kind, name,
  ref, flags (`o` one-way, `r` roundabout, `b` bridge, `t` tunnel), then the points. Overpass
  answers 406 without a `User-Agent` header.
- **A map in the Karoo's format:** any file from `download.mapsforge.org/maps/v5/`. The small
  `australia-oceania/new-caledonia.map` (51 MB) was used to test the reader.
- **Rides:** `ride-<id>.csv`, `time_s,lat,lng,altitude_m`.

## Credits

Road data is from OpenStreetMap contributors (ODbL). Elevation data is from Terrain Tiles on AWS
Open Data. Map files are read with Mapsforge (LGPL-3.0), unmodified. Grade colours follow the
Karoo's own climb colours.
