# MiRobotAI v0.6

LOOI-style face + working Mi Robot Builder BLE motor control.

## v0.6 personality changes
- No single visible mood score.
- Three hidden internal meters: happiness, curiosity, boredom.
- The meters are never shown on the face or debug panel.
- Personality changes are deliberately slow.
- The first 10 minutes of no interaction cause almost no change.
- Boredom grows gradually over tens of minutes, not every minute.
- Becoming genuinely upset requires being ignored for a long time.
- Talking, touching/petting, and future camera events can influence the three meters independently.

## Existing controls
- Normal screen: LOOI-inspired eyes only.
- Tap face: positive interaction / brief excited expression.
- Long-press face: hidden robot control panel.
- Motor controls and BLE behavior remain the same as the working v0.5 base.
