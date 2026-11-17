# Notices

## Third-party libraries

This application uses AndroidX (Compose, Navigation, Lifecycle, DataStore, WorkManager, Glance),
Material Components for Compose and Kotlin Coroutines, all under the Apache License 2.0.

It contains no AI model and no model runtime. Builds up to 1.3.10 carried assistant code that could
call Google's Gemini API with a key the user supplied; that code was unreachable once the
Assistant tab was removed in September 2026, and was deleted in 1.3.11.
