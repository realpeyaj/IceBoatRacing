# 🛥️ IceBoatRacing

![Version](https://img.shields.io/badge/Paper-1.21+-blue?style=for-the-badge&logo=paper)
![Maintenance](https://img.shields.io/badge/Maintained%20by-peyaj-orange?style=for-the-badge)

**A high-precision, multi-arena Ice Boat Racing plugin for Paper 1.21+.**

---

## ❄️ What does it do?

**IceBoatRacing** provides a seamless competitive racing experience, bringing the mechanics of popular boat racing servers to your own. It solves common issues like collision frustration and lag-induced checkpoint skipping.

### ✨ Key Features

* 🏔️ **Multi-Arena Support**
    * Run multiple races simultaneously on different tracks (e.g., "FrostyPeaks", "LavaRun").
* 🏁 **Flexible Race Modes**
    * Supports both **DEFAULT** (Sprint/Point-to-Point) and **LAP** (Looping) modes.
* ⚡ **Ray-Traced Physics**
    * Uses vector math to detect checkpoints even at extreme speeds (**100km/h+**). No more "skipping" checkpoints due to server lag.
* 🪄 **Visual Editor**
    * An innovative Wand tool (`/race admin wand`) to build tracks. Visualize spawns, checkpoints, and finish lines as floating particles in real-time.
* 📊 **Live Leaderboard**
    * Flicker-free sidebar scoreboard showing live ranks, speed, and lap times, plus an Action Bar speedometer.
* 🎵 **Immersive Audio**
    * Plays custom resource pack sounds/music during the race loop.

---

## 📥 Installation

1.  **Prerequisite:** Ensure your server is running **Paper 1.21+**.
2.  Download the `IceBoatRacing.jar` file.
3.  Place it in your server's `/plugins` folder.
4.  **Restart** the server.
    > ⚠️ **Warning:** Do not use `/reload`. Always fully restart when installing new plugins.
5.  *(Optional)* Configure `config.yml` to adjust global detection radius or music settings.

---

## 🛠️ Setup Guide

### 1. Create the Arena
Run the create command to initialize a new track data file.

/race admin create arena <DEFAULT|LAP>

Example:
/race admin create coconut_mall LAP

### 2. Enter Edit Mode
- Grab the editor wand and select your arena.

/race admin wand 
- Gives you the Blaze Rod tool

/race admin edit coconut_mall

>  Note: Entering edit mode automatically turns on the Particle Visualizer.

### 3. Build the Track (Using the Wand)
Use the Blaze Rod to set points.

### Controls:

Shift + Right-Click (Air): Cycle Edit Modes (Spawns, Checkpoints, Finish Line, Lobbies).

Right-Click (Block): ADD a point at the target block.

Left-Click (Block): REMOVE the point at the target block.

### Recommended Build Order:

PRE-LOBBY → Set where players wait before the race starts.

SPAWN POINTS → Click blocks to add starting positions for boats.

CHECKPOINTS → Click along your ice track to add checkpoints.

FINISH POS 1 & 2 → Click the top-left and bottom-right corners of your finish line gate to create a detection zone.

MAIN LOBBY → Set where players go after the race ends.

### 4. Finalize
Configure the specific settings for your new arena:

#### Set lap count (if LAP mode)
/race admin setlaps coconut_mall 3

#### Configure auto-start (optional)
/race admin setminplayers coconut_mall 2

#### Exit edit mode to save and hide particles
/race admin stopedit

## 📜 Commands & Permissions

### Basic Commands and Permission

/race list,
>  race.use
- List all available arenas.
/race join arena
>  race.use
- Join a race lobby.
/race leave
>  race.use
- Leave the current lobby or race.
/race cp
>  race.use
- Respawn at your last checkpoint.

### Admin Command and Description

/race start arena
- Force start a race immediately.
  
/race stop arena
- Force stop a race and eject all players.
  
/race admin create arena <type>
- Create a new arena (DEFAULT or LAP).
 
/race admin delete arena
- Delete an arena and its data.
 
/race admin wand
- Get the setup wand tool.
  
/race admin edit arena
- Enter edit mode for a specific arena.
  
/race admin stopedit
- Exit edit mode and hide visualizers.
  
/race admin visualize arena
- Toggle particle visualizers without editing.
  
/race admin setlaps arena value
- Set the number of laps.
  
/race admin setminplayers arena value
- Set minimum players for auto-start (Default: 2).
  
/race admin setautostart arena value
- Set countdown seconds for auto-start (Default: 30s).
  
/race admin setradius value
- Set global checkpoint detection radius (Default: 25.0).
  
