# Basic Usage

## Browser Tree Structure

The audio browser presents content as a navigable tree. This structure is required by Android Auto and CarPlay to display your content, and is also handy for apps to consume directly.

Each node can be **browsable** (a folder you can navigate into) or **playable** (a track you can play).

```mermaid
graph TD
    Root[Root]
    Root --> Favorites[Favorites]
    Root --> Recent[Recently Played]
    Root --> Browse[Browse]

    Browse --> Jazz[Jazz]
    Browse --> Rock[Rock]

    Jazz --> J1[Smooth Floret FM]
    Jazz --> J2[The Stalk 88.5]
    Rock --> R90[Vintage]
    Rock --> R1[Crunchy Greens]
    Rock --> R2[Stem City Radio]
    R90 --> R3[Funky Floret 101.3]
    R90 --> R4[Wilted Greens FM]

    classDef browsable fill:#e3f2fd,stroke:#1976d2
    classDef playable fill:#e8f5e9,stroke:#388e3c

    class Root,Favorites,Recent,Browse,Jazz,Rock,R90 browsable
    class J1,J2,R1,R2,R3,R4 playable
```

<div style="display: flex; gap: 1rem; margin-top: 0.5rem; font-size: 0.9em;">
  <span><span style="display: inline-block; width: 12px; height: 12px; background: #e3f2fd; border: 1px solid #1976d2; margin-right: 4px;"></span> Browsable (folder)</span>
  <span><span style="display: inline-block; width: 12px; height: 12px; background: #e8f5e9; border: 1px solid #388e3c; margin-right: 4px;"></span> Playable (track)</span>
</div>

## Key Concepts

- **Browsable items** have a `url` that resolves to more children
- **Playable items** have a `src` (the audio stream URL)

## Player & Queue

The player maintains a queue of tracks. When you play a track from the browser, the queue is populated with the playable items from that context — this allows external next/previous buttons to work (Android Auto, CarPlay, headphones, etc.).

```mermaid
graph LR
    subgraph Queue
        T1[Fresh Sprout FM]
        T2[The Seedling 91.2]
        T3[Baby Greens Radio]
        T4[Lil Bud 103.5]
    end

    T2 -.->|currently playing| NP[Now Playing]

    classDef track fill:#f1f8e9,stroke:#388e3c
    classDef current fill:#fff3e0,stroke:#f57c00,stroke-width:2px
    classDef np fill:#fff3e0,stroke:#f57c00

    class T1,T3,T4 track
    class T2 current
    class NP np
```
