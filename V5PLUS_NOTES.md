# Welly V5+ Stable Patch

Base: exact V5 commit beb0d577b754f44502070f933220d4f2e4a24d19

Changes:
- V5 app tree restored first; later-version-only source is removed.
- Tilt / upside-down sensor behavior disabled.
- Perception continues while a behavior runs.
- No normal reaction queue; after behavior + settle, newest world state only is evaluated.
- Open-palm STOP may interrupt.
- Smile, wink, close-face require release/neutral before rearm.
- Ordinary face loss no longer starts body-search movement; Follow/Search only.
- Fork behavior packs get non-repeating variations.
- Speech choices avoid immediate exact repeats.
- Camera/ML pipeline capped around 10 fps.
- Test mode shows fork RUNNING/DONE state.
- English-only voice path with real TTS completion.
- Fake pitch-preset voice changer removed.
- Richer lightweight eye expressions.

Voice note:
This patch does NOT pretend Android TTS is a custom neural cute robot voice.
A real custom Welly neural voice should be added later as its own tested module.

Version:
versionCode 9
versionName 0.5.1-stable

LOOI-inspired living behavior layer (kept deliberately small):
- Context chooses the behavior family; random variation only happens INSIDE that family.
- Touch, petting, wave, praise, peace, love, attention and self-play packs.
- 3-4 coherent variants per pack with immediate-repeat avoidance.
- Most packs can choose silent/non-verbal variants, reducing speech spam.
- Mood, annoyance, social need, boredom and energy weight speech/motion intensity.
- TTS ownership is claimed synchronously to close the tiny callback race before onStart.
- This is a compact pack foundation, not a 1200-action clone.
- Version bumped to 0.5.2-living (code 10).
