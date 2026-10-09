# SignalDock diagram style

Use the approved white background, navy text, and teal accents. The existing HTML and SVG files define the layout.

| Role | Value |
| --- | --- |
| Paper | `#ffffff` |
| Secondary paper | `#f3f7f8` |
| Ink | `#162a43` |
| Muted | `#536579` |
| Accent | `#087f8c` |
| Accent tint | `#eaf5f5` |
| API link | `#315f96` |
| Rule | `#bbc9d2` |

## Presentation rules

- Use system fonts. Do not require external font requests.
- Limit focal accents to two nodes per diagram. Use orthogonal connectors and clear spacing.
- Keep architecture and chronological sequence in separate diagrams. Put supporting detail in the body text.
- Give each SVG a title and description. On small screens, scroll only the diagram region horizontally.
- Explain the diagrams' main meaning in the Markdown content too.
- Keep node labels at 16px and supporting labels at 12px. Shorten English labels or adjust boxes when needed; do not reduce the font size to fit.

Design guidance: [diagram-design](https://github.com/cathrynlavery/diagram-design), MIT.
