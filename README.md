# SimView

SimView is a Hytale server plugin that separates **simulation distance** chunks from **view distance** chunks.

It keeps a controlled simulation distance for gameplay while streaming additional distant chunks as view-only "cold" chunks. This lets players see farther with much lower server cost than simulating everything in full.

## Build

```bash
./gradlew build
```

Output jar is created in `build/libs/` with base name `SimView`.

## Install

1. Build the plugin jar.
2. Place the jar in your server's plugin/mod location.
3. Start the server once so SimView can generate its config file.

## Configuration

On startup, SimView creates:

- `SimView/config/simview.json`

SimView resolves this under the parent of the plugin data directory (so it has a stable shared root named `SimView`).

### Default config

```json
{
  "core": {
    "enabled": true,
    "target": {
      "view-distance-chunks": 32,
      "simulation-distance-chunks": 32
    },
    "limits": {
      "minimum": {
        "view-distance-chunks": 0,
        "simulation-distance-chunks": 0
      },
      "maximum": {
        "view-distance-chunks": 96,
        "simulation-distance-chunks": 32
      }
    }
  },
  "auto-adjustment": {
    "mode": {
      "view": "off",
      "simulation": "off"
    },
    "cadence": {
      "ticks-per-check": 600,
      "startup-delay-ticks": 2400
    },
    "checks": {
      "view": {
        "for-increase": 10,
        "for-decrease": 1
      },
      "simulation": {
        "for-increase": 10,
        "for-decrease": 1
      }
    },
    "proactive": {
      "global-cold-chunk-count-target": 120000,
      "global-ticking-chunk-count-target": 0
    },
    "reactive": {
      "increase-mspt-threshold": 40.0,
      "decrease-mspt-threshold": 47.0,
      "mspt-collection-period-ticks": 1200,
      "use-mspt-prediction": true,
      "mspt-prediction-history-minutes": 30
    }
  },
  "cold-chunk-streaming": {
    "generate-missing": true,
    "despawn-entities": true,
    "budget": {
      "chunk-sends-per-second": 96,
      "chunk-sends-per-tick": 8,
      "cold-chunk-loads-in-flight": 64
    }
  },
  "speeding-adjustments": {
    "not-send-blocks-per-tick": 1.2,
    "cooldown-ticks": 40,
    "budget": {
      "chunk-sends-per-second": 24,
      "chunk-sends-per-tick": 2
    }
  }
}
```

### Key options

- `core.target.view-distance-chunks`: desired SimView view distance.
- `core.target.simulation-distance-chunks`: desired simulation distance (what Hytale runtime view cap is set to).
- `core.limits.minimum|maximum.*`: clamp ranges for view and simulation targets.
- `auto-adjustment.mode.view|simulation`: auto mode per target (`off`, `proactive`, `reactive`, `mixed`).
- `auto-adjustment.cadence.*`: shared startup delay and check interval.
- `auto-adjustment.checks.view|simulation.for-increase|for-decrease`: anti-flap consecutive-check gates.
- `auto-adjustment.proactive.*`: proactive chunk-count targets.
- `auto-adjustment.reactive.*`: MSPT thresholds, collection cadence, and prediction controls.
- `cold-chunk-streaming.budget.*`: cold chunk send/load budgets.
- `cold-chunk-streaming.generate-missing`: allows generating cold chunks if not present.
- `cold-chunk-streaming.despawn-entities`: keeps entity simulation/visibility at hot radius.
- `speeding-adjustments.*`: temporary tighter send budgets while players move quickly.

## Auto Mode

SimView tracks two active targets independently:

- **active target simulation distance** (ticking/hot chunks)
- **active target view distance** (cold + hot visible chunks)

Runtime constraints are always enforced:

- simulation distance target is clamped by simulation min/max.
- view distance target is clamped by view min/max.
- simulation distance target will never exceed view distance target.
- view distance target will never go below simulation distance target.

Both auto modes share check cadence:

- `auto-adjustment.cadence.startup-delay-ticks`
- `auto-adjustment.cadence.ticks-per-check`

Both auto modes also use consecutive-check gating before a change applies:

- view: `auto-adjustment.checks.view.for-increase|for-decrease`
- simulation: `auto-adjustment.checks.simulation.for-increase|for-decrease`

### Mode behavior

- `off`
  - Holds fixed configured targets.
- `proactive`
  - View mode follows `auto-adjustment.proactive.global-cold-chunk-count-target`.
  - Simulation mode follows `auto-adjustment.proactive.global-ticking-chunk-count-target` (disabled when target is `0`).
- `reactive`
  - Uses MSPT thresholds:
    - `<= auto-adjustment.reactive.increase-mspt-threshold`: candidate increase
    - `>= auto-adjustment.reactive.decrease-mspt-threshold`: candidate decrease
    - in-between: stay
  - Optional prediction (`auto-adjustment.reactive.use-mspt-prediction`) blocks risky increases using historical MSPT-per-chunk slopes.
- `mixed`
  - Combines proactive and reactive checks (stronger signal wins).

### Practical tuning guidance

- Keep `auto-adjustment.reactive.decrease-mspt-threshold` higher than `auto-adjustment.reactive.increase-mspt-threshold`.
- Keep simulation decrease checks low for faster overload shedding.
- Increase view increase checks if visible distance climbs too aggressively.
- Use different modes for each target to shape load behavior and hot:cold ratio dynamically.

## Commands

- `/simview`
  - Permission group: `hytale:None`
  - Shows current effective distances, budgets, and both auto-tuner states.
- `/simviewreload`
  - Permission group: `hytale:Admin`
  - Reloads `simview.json`, reapplies caps, and resets tuner state.
