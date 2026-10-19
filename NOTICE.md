# Notices

## Gemma

Schedule's assistant runs a Gemma model on-device. The model is **not** included in this
application — it is downloaded or imported by the user after they accept Google's licence.

> Gemma is provided under and subject to the Gemma Terms of Use found at
> [ai.google.dev/gemma/terms](https://ai.google.dev/gemma/terms).

If you re-host the model file so this app can download it (the intended setup — the upstream
repository requires a signed-in licence acceptance and cannot be fetched automatically), you are
redistributing Gemma. The Gemma Terms permit that, and §3.1 requires you to:

- give recipients a copy of the Gemma Terms of Use;
- carry the use restrictions in §3.2 forward as an enforceable provision;
- include this notice with the distribution;
- mark any modified model files as modified.

Do not remove or alter this file when publishing a build, and keep it alongside any copy of the
model you host.

Use is also subject to Google's
[Gemma Prohibited Use Policy](https://ai.google.dev/gemma/prohibited_use_policy).

## Third-party libraries

This application uses AndroidX (Compose, Navigation, Lifecycle, DataStore, WorkManager, Glance) and
MediaPipe Tasks GenAI, all under the Apache License 2.0, and the Kizitonwose Calendar library under
the MIT License.
